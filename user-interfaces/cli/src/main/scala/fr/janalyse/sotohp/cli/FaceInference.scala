package fr.janalyse.sotohp.cli

import fr.janalyse.sotohp.core.*
import fr.janalyse.sotohp.media.imaging.BasicImaging
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.processor.{FacesDetectionIssue, NormalizeProcessor}
import fr.janalyse.sotohp.search.SearchService
import fr.janalyse.sotohp.service.MediaService
import zio.*
import zio.config.typesafe.*
import zio.lmdb.LMDB

import java.awt.image.BufferedImage
import java.time.temporal.ChronoUnit
import java.time.temporal.ChronoUnit.{MONTHS, YEARS}
import java.time.{Instant, OffsetDateTime}
import scala.io.AnsiColor.*

/*
 * This is a one-shot tool to fix faces rotation from originals, which was not initially done
 */
object FaceInference extends CommonsCLI {

  override def run =
    logic
      .provide(
        LMDB.live,
        SearchService.live,
        MediaService.live,
        Scope.default
      )

  object CosineDistance {
    def d(f1: Array[Float], f2: Array[Float]): Double = {
      var dot        = 0.0
      var n1         = 0.0
      var n2         = 0.0
      var i          = 0
      while (i < f1.length) {
        val v1 = f1(i)
        val v2 = f2(i)
        dot += v1 * v2
        n1 += v1 * v1
        n2 += v2 * v2
        i += 1
      }
      val similarity = dot / (Math.sqrt(n1) * Math.sqrt(n2))
      1.0 - similarity
    }
  }

  val distance = CosineDistance

  def fixFaceWithMissingFeatures(): ZIO[MediaService, Exception, Unit] = {
    MediaService
      .originalList()
      .mapZIO(original => MediaService.originalFacesFeatures(original.id).ignoreLogged)
      .runCollect
      .unit
  }

  // -------------------------------------------------------------------------------------------------------------------
  def featuresForIdentifiedFaces(): ZIO[MediaService, Exception, Chunk[(Face, FaceFeatures)]] = {
    for {
      identifiedFaces <- MediaService.faceList().filter(_.identifiedPersonId.isDefined).runCollect
      featureByFace   <- ZIO.foreach(identifiedFaces) { detectedFace =>
                           MediaService
                             .faceFeaturesGet(detectedFace.faceId)
                             .map(feature => feature.map(detectedFace -> _))
                         }
    } yield featureByFace.flatten
  }

  def featuresForUnknowFaces(): ZIO[MediaService, Exception, Chunk[(Face, FaceFeatures)]] = {
    for {
      identifiedFaces <- MediaService.faceList().filter(_.identifiedPersonId.isEmpty).runCollect
      featureByFace   <- ZIO.foreach(identifiedFaces) { detectedFace =>
                           for {
                             original  <- MediaService.originalGet(detectedFace.originalId)
                             dimension  = original.flatMap(_.dimension)
                             faceWidth  = dimension.map(_.width.value * detectedFace.box.width.value)
                             faceHeight = dimension.map(_.height.value * detectedFace.box.height.value)
                             enoughBig  = dimension.isEmpty || (faceWidth.getOrElse(0d) > 70d && faceHeight.getOrElse(0d) > 70d)
                             tuple     <- MediaService
                                            .faceFeaturesGet(detectedFace.faceId)
                                            .map(feature => feature.map(detectedFace -> _))
                           } yield tuple.filter(_ => enoughBig)
                         }
    } yield featureByFace.flatten
  }

  // Maximum cosine distance below which two faces are considered close enough to be the same person.
  val maxMatchDistance        = 0.16
  val maxIgnoredMatchDistance = 0.20

  // Faces explicitly marked as ignored by the user. Their features are used to veto inference:
  // an unidentified face too near an ignored one gets no inferred person.
  def featuresForIgnoredFaces(): ZIO[MediaService, Exception, Chunk[(Face, FaceFeatures)]] = {
    for {
      ignoredFaces  <- MediaService.faceList().filter(_.inferredIgnore.contains(true)).runCollect
      featureByFace <- ZIO.foreach(ignoredFaces) { detectedFace =>
                         MediaService
                           .faceFeaturesGet(detectedFace.faceId)
                           .map(feature => feature.map(detectedFace -> _))
                       }
    } yield featureByFace.flatten
  }

  // A face is "too near an ignored face" when its features are within the match distance of any ignored face.
  def isNearIgnoredFace(ignoredFaces: Chunk[(Face, FaceFeatures)])(face: Face, faceFeatures: FaceFeatures): Boolean = {
    ignoredFaces.exists { (ignoredFace, ignoredFeatures) =>
      ignoredFace.faceId != face.faceId && distance.d(faceFeatures.features, ignoredFeatures.features) <= maxIgnoredMatchDistance
    }
  }

  def identifyFace(knownFaces: Chunk[(Face, FaceFeatures)], ignoredFaces: Chunk[(Face, FaceFeatures)])(face: Face, faceFeatures: FaceFeatures): ZIO[MediaService, Exception, Boolean] = {
    val (knownFace, knownFaceFeature) = knownFaces.minBy((knownFace, knownFaceFeatures) => distance.d(faceFeatures.features, knownFaceFeatures.features))
    val foundDistance                 = distance.d(faceFeatures.features, knownFaceFeature.features)

    for {
      now                <- Clock.currentDateTime
      inferredPersonId    = knownFace.identifiedPersonId
                              .filter(_ => foundDistance < maxMatchDistance)
                              .filterNot(_ => isNearIgnoredFace(ignoredFaces)(face, faceFeatures))
      updatedFace         = face.copy(
                              inferredIdentifiedPersonId = inferredPersonId,
                              inferredTimestamp = inferredPersonId.map(_ => now)
                            )
      isFreshlyIdentified = (
                              updatedFace.inferredIdentifiedPersonId.isDefined
                                && face.identifiedPersonId.isEmpty
                                && face.inferredIdentifiedPersonId.isEmpty
                            )
      _                  <- MediaService
                              .faceUpdate(face.faceId, updatedFace)
                              .when(
                                //updatedFace != face
                                updatedFace.inferredIdentifiedPersonId != face.inferredIdentifiedPersonId
                              )
    } yield isFreshlyIdentified
  }

  def identifyFaceWithConsensus(knownFaces: Chunk[(Face, FaceFeatures)], ignoredFaces: Chunk[(Face, FaceFeatures)])(face: Face, faceFeatures: FaceFeatures): ZIO[MediaService, Exception, Boolean] = {
    val shortests =
      knownFaces
        .map((knownFace, knownFaceFeatures) => (knownFace.identifiedPersonId.get, knownFaceFeatures, distance.d(faceFeatures.features, knownFaceFeatures.features)))
        .filter { (_, _, distance) => distance <= maxMatchDistance }
        .sortBy { (_, _, distance) => distance }
        .take(2)

//    val bestCandidate:Option[PersonId] = {
//      shortests
//        .groupBy{(personId, faceFeatures, distance) => personId}
//        .maxByOption{(personId, faces) => (faces.size, 1d-faces.map{(_,_,dist)=>dist}.min)}
//        .map{ (personId, faces) => personId}
//    }

    val bestCandidate: Option[(id: PersonId, dist: Double)] = {
      // veto inference when the face is too near a face the user marked as ignored
      if (isNearIgnoredFace(ignoredFaces)(face, faceFeatures)) None
      else
        shortests
          .groupBy { (personId, _, _) => personId } match {
          case result if result.size == 1 => // only one person identified, consensus reached
            result.values.head
              .minByOption((personId, _, dist) => dist) // select the best found similarity distance
              .map((personId, _, dist) => personId -> dist)

          case _ => None
        }
    }

    for {
      now                <- Clock.currentDateTime
      inferredPersonId    = bestCandidate.map(_.id)
      inferredTimestamp   = inferredPersonId match {
                              // keep the original inference date when the inferred person is unchanged
                              case Some(id) if face.inferredIdentifiedPersonId.contains(id) => face.inferredTimestamp.orElse(Some(now))
                              case Some(_)                                                  => Some(now)
                              case None                                                     => None
                            }
      updatedFace         = face.copy(
                              inferredIdentifiedPersonId = inferredPersonId,
                              inferredIdentifiedPersonConfidence = bestCandidate.map(1d - _.dist),
                              inferredTimestamp = inferredTimestamp
                            )
      isFreshlyIdentified = (
                              updatedFace.inferredIdentifiedPersonId.isDefined
                                && face.identifiedPersonId.isEmpty
                                && face.inferredIdentifiedPersonId.isEmpty
                            )
      _                  <- MediaService
                              .faceUpdate(face.faceId, updatedFace)
                              .when(
                                //updatedFace != face
                                updatedFace.inferredIdentifiedPersonId != face.inferredIdentifiedPersonId
                              )
    } yield isFreshlyIdentified
  }

  // -------------------------------------------------------------------------------------------------------------------
  val logic = ZIO.logSpan("Infer person identification from faces features and already identified faces") {
    for {
      // _              <- fixFaceWithMissingFeatures()
      // _                  <- ZIO.attemptBlocking(Thread.sleep(120.minutes)) // TODO temporary hack top be removed
      knownFaces     <- featuresForIdentifiedFaces()
      unknownFaces   <- featuresForUnknowFaces()
      ignoredFaces   <- featuresForIgnoredFaces()
      now            <- Clock.currentDateTime
      alreadyInferred = unknownFaces
                          .filter((face, _) => face.inferredIdentifiedPersonId.isDefined)
      tocheck         = unknownFaces
                          .filterNot((face, _) => face.inferredIgnore.contains(true)) // don't re-infer faces the user marked as ignored
      // .filter((face, _) => face.inferredIdentifiedPersonId.isEmpty)
      // .filter((face, _) => face.timestamp.isAfter(now.minus(20, ChronoUnit.DAYS)))
      // .filter((face, _) => face.timestamp.isAfter(now.minus(6, ChronoUnit.MONTHS)))
      personsCount       <- MediaService.personList().runCount
      _                  <- Console.printLine(s"$personsCount people records")
      _                  <- Console.printLine(s"${knownFaces.size} identified and confirmed faces")
      _                  <- Console.printLine(s"${ignoredFaces.size} ignored faces (used to veto inference)")
      _                  <- Console.printLine(s"${unknownFaces.size} unknown faces with ${alreadyInferred.size} inferred and unconfirmed")
      newIdentifiedCount <- zio.stream.ZStream
                              .from(tocheck)
                              // .filter((face, _) => face.inferredIdentifiedPersonId.isEmpty) // avoid recompute, comment to force recompute
                              // .mapZIO((face, faceFeatures) => identifyFace(knownFaces, ignoredFaces)(face, faceFeatures))
                              .mapZIO((face, faceFeatures) => identifyFaceWithConsensus(knownFaces, ignoredFaces)(face, faceFeatures))
                              .filter(_ == true)
                              .runCount
      _                  <- Console.printLine(s"$newIdentifiedCount new faces inferred")
      _                  <- ZIO.logInfo(s"done")
    } yield ()
  }

}
