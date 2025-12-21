package fr.janalyse.sotohp.cli

import fr.janalyse.sotohp.core.*
import fr.janalyse.sotohp.media.imaging.BasicImaging
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.processor.model.{DetectedFace, FaceFeatures, FaceId, PersonId}
import fr.janalyse.sotohp.processor.{FacesDetectionIssue, NormalizeProcessor}
import fr.janalyse.sotohp.search.SearchService
import fr.janalyse.sotohp.service.MediaService
import zio.*
import zio.config.typesafe.*
import zio.lmdb.LMDB

import java.awt.image.BufferedImage
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

  val distance = new smile.math.distance.EuclideanDistance()

  def fixFaceWithMissingFeatures(): ZIO[MediaService, Exception, Unit] = {
    MediaService
      .originalList()
      .mapZIO(original => MediaService.originalFacesFeatures(original.id).ignoreLogged)
      .runCollect
      .unit
  }

  // -------------------------------------------------------------------------------------------------------------------
  def featuresForIdentifiedFaces(): ZIO[MediaService, Exception, Chunk[(DetectedFace, FaceFeatures)]] = {
    for {
      identifiedFaces <- MediaService.faceList().filter(_.identifiedPersonId.isDefined).runCollect
      featureByFace   <- ZIO.foreach(identifiedFaces) { detectedFace =>
                           MediaService
                             .faceFeaturesGet(detectedFace.faceId)
                             .map(feature => feature.map(detectedFace -> _))
                         }
    } yield featureByFace.flatten
  }

  def featuresForUnknowFaces(): ZIO[MediaService, Exception, Chunk[(DetectedFace, FaceFeatures)]] = {
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

  def identifyFace(knownFaces: Chunk[(DetectedFace, FaceFeatures)])(face: DetectedFace, faceFeatures: FaceFeatures): ZIO[MediaService, Exception, Boolean] = {
    val (knownFace, knownFaceFeature) = knownFaces.minBy((knownFace, knownFaceFeatures) => distance.d(faceFeatures.features, knownFaceFeatures.features))
    val foundDistance                 = distance.d(faceFeatures.features, knownFaceFeature.features)

    val updatedFace         = face.copy(
      inferredIdentifiedPersonId = knownFace.identifiedPersonId
        .filter(_ => foundDistance < 0.65)
    )
    val isFreshlyIdentified = (
      updatedFace.inferredIdentifiedPersonId.isDefined
        && face.identifiedPersonId.isEmpty
        && face.inferredIdentifiedPersonId.isEmpty
    )

    MediaService
      .faceUpdate(face.faceId, updatedFace)
      .when(updatedFace != face)
      .as(isFreshlyIdentified)
  }

  def identifyFaceWithConsensus(knownFaces: Chunk[(DetectedFace, FaceFeatures)])(face: DetectedFace, faceFeatures: FaceFeatures): ZIO[MediaService, Exception, Boolean] = {
    val shortests =
      knownFaces
        .map((knownFace, knownFaceFeatures) => (knownFace.identifiedPersonId.get, knownFaceFeatures, distance.d(faceFeatures.features, knownFaceFeatures.features)))
        .filter { (_, _, distance) => distance <= 0.675 }
        .sortBy { (_, _, distance) => distance }
        .take(3)

//    val bestCandidate:Option[PersonId] = {
//      shortests
//        .groupBy{(personId, faceFeatures, distance) => personId}
//        .maxByOption{(personId, faces) => (faces.size, 1d-faces.map{(_,_,dist)=>dist}.min)}
//        .map{ (personId, faces) => personId}
//    }

    val bestCandidate: Option[(id: PersonId, dist: Double)] = {
      shortests
        .groupBy { (personId, _, _) => personId } match {
        case result if result.size == 1 => // only one person identified, consensus reached
          result.values.head
            .minByOption((personId, _, dist) => dist) // select the best found similarity distance
            .map((personId, _, dist) => personId -> dist)

        case _ => None
      }
    }

    val updatedFace = face.copy(
      inferredIdentifiedPersonId = bestCandidate.map(_.id),
      inferredIdentifiedPersonConfidence = bestCandidate.map(1d - _.dist)
    )

    val isFreshlyIdentified = (
      updatedFace.inferredIdentifiedPersonId.isDefined
        && face.identifiedPersonId.isEmpty
        && face.inferredIdentifiedPersonId.isEmpty
    )

    MediaService
      .faceUpdate(face.faceId, updatedFace)
      .when(updatedFace != face)
      .as(isFreshlyIdentified)
  }

  // -------------------------------------------------------------------------------------------------------------------
  val logic = ZIO.logSpan("Infer person identification from faces features and already identified faces") {
    for {
      // _              <- fixFaceWithMissingFeatures()
      knownFaces         <- featuresForIdentifiedFaces()
      unknownFaces       <- featuresForUnknowFaces()
      alreadyInferred     = unknownFaces.filter((face, _) => face.inferredIdentifiedPersonId.isDefined && face.identifiedPersonId.isEmpty)
      tocheck             = unknownFaces
      personsCount       <- MediaService.personList().runCount
      _                  <- Console.printLine(s"$personsCount people records")
      _                  <- Console.printLine(s"${knownFaces.size} identified and confirmed faces")
      _                  <- Console.printLine(s"${unknownFaces.size} unknown faces with ${alreadyInferred.size} inferred and unconfirmed")
      newIdentifiedCount <- zio.stream.ZStream
                              .from(tocheck)
                              // .filter((face, _) => face.inferredIdentifiedPersonId.isEmpty) // avoid recompute, comment to force recompute
                              // .mapZIO((face, faceFeatures) => identifyFace(knownFaces)(face, faceFeatures))
                              .mapZIO((face, faceFeatures) => identifyFaceWithConsensus(knownFaces)(face, faceFeatures))
                              .filter(_ == true)
                              .runCount
      _                  <- Console.printLine(s"$newIdentifiedCount new faces inferred")
      _                  <- ZIO.logInfo(s"done")
    } yield ()
  }

}
