package fr.janalyse.sotohp.cli

import fr.janalyse.sotohp.core.*
import fr.janalyse.sotohp.media.imaging.BasicImaging
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.processor.{FacesDetectionIssue, NormalizeProcessor}
import fr.janalyse.sotohp.search.SearchService
import fr.janalyse.sotohp.service.MediaService
import fr.janalyse.sotohp.service.MediaServiceLive.given // brings `KeyCodec[FaceId]` into scope, needed by `LMDBVectorIndex.create[FaceId]`
import zio.*
import zio.config.typesafe.*
import zio.lmdb.LMDB
import zio.lmdb.vector.{HnswParams, LMDBVectorIndex, VectorMetric}

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

  // How many nearest known faces we pull from the vector index before applying `maxMatchDistance`/consensus.
  // Comfortably more than the 2 candidates consensus actually needs, so a threshold cutoff can never hide a
  // legitimate match sitting just behind a closer-but-too-far neighbor.
  val nearestCandidatesToConsider = 8

  // How many unknown faces are matched against the index concurrently.
  val searchParallelism = java.lang.Runtime.getRuntime.availableProcessors()

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
  // Only ~900 entries in practice, so a plain scan stays cheap - not worth indexing.
  def isNearIgnoredFace(ignoredFaces: Chunk[(Face, FaceFeatures)])(face: Face, faceFeatures: FaceFeatures): Boolean = {
    ignoredFaces.exists { (ignoredFace, ignoredFeatures) =>
      ignoredFace.faceId != face.faceId && VectorMetric.Cosine.distance(faceFeatures.features, ignoredFeatures.features) <= maxIgnoredMatchDistance
    }
  }

  def identifyFace(vectorIndex: LMDBVectorIndex[FaceId], knownFaceById: Map[FaceId, Face], ignoredFaces: Chunk[(Face, FaceFeatures)])(face: Face, faceFeatures: FaceFeatures): ZIO[MediaService, Exception, Boolean] = {
    for {
      nearest            <- vectorIndex.searchNearest(faceFeatures.features, k = 1).orDieWith(err => new RuntimeException(err.toString))
      now                <- Clock.currentDateTime
      inferredPersonId    = nearest.headOption
                              .flatMap { (nearestFaceId, foundDistance) =>
                                knownFaceById(nearestFaceId).identifiedPersonId.filter(_ => foundDistance < maxMatchDistance)
                              }
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
                                // updatedFace != face
                                updatedFace.inferredIdentifiedPersonId != face.inferredIdentifiedPersonId
                              )
    } yield isFreshlyIdentified
  }

  def identifyFaceWithConsensus(vectorIndex: LMDBVectorIndex[FaceId], knownFaceById: Map[FaceId, Face], ignoredFaces: Chunk[(Face, FaceFeatures)])(face: Face, faceFeatures: FaceFeatures): ZIO[MediaService, Exception, Boolean] = {
    for {
      nearest                                            <- vectorIndex.searchApproximate(faceFeatures.features, k = nearestCandidatesToConsider).orDieWith(err => new RuntimeException(err.toString))
      shortests                                           = nearest
                                                              .collect { case (faceId, dist) if dist <= maxMatchDistance => (knownFaceById(faceId).identifiedPersonId.get, dist) }
                                                              .take(2)

      bestCandidate: Option[(id: PersonId, dist: Double)] = {
        // veto inference when the face is too near a face the user marked as ignored
        if (isNearIgnoredFace(ignoredFaces)(face, faceFeatures)) None
        else
          shortests
            .groupBy { (personId, _) => personId } match {
            case result if result.size == 1 => // only one person identified, consensus reached
              result.values.head
                .minByOption((_, dist) => dist) // select the best found similarity distance
                .map((personId, dist) => personId -> dist)

            case _ => None
          }
      }

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
                                // updatedFace != face
                                updatedFace.inferredIdentifiedPersonId != face.inferredIdentifiedPersonId
                              )
    } yield isFreshlyIdentified
  }

  // A scratch vector index rebuilt from the current `knownFaces` on every run (mirrors how `knownFaces` itself is
  // always freshly recomputed) - dropped again once the run is done, so it doesn't linger as clutter in the database.
  private val vectorIndexCollectionName = "faceInferenceKnownFacesVectorIndexTmp"

  def withKnownFacesVectorIndex[R, E >: VectorIndexAcquireFailure, A](knownFaces: Chunk[(Face, FaceFeatures)])(use: LMDBVectorIndex[FaceId] => ZIO[R, E, A]): ZIO[R & LMDB, E, A] = {
    val dimension = knownFaces.headOption.map((_, features) => features.features.length).getOrElse(512)
    ZIO.acquireReleaseWith(
      LMDB.collectionDrop(vectorIndexCollectionName).ignore *>
        LMDBVectorIndex
          .create[FaceId](vectorIndexCollectionName, dimension, VectorMetric.Cosine, failIfExists = false)
          .mapError(err => VectorIndexAcquireFailure(err.toString))
          .tap(index =>
            ZIO
              .foreachDiscard(knownFaces) { (face, features) => index.insert(face.faceId, features.features) }
              .orDieWith(err => new RuntimeException(err.toString))
              .timed
              .flatMap((elapsed, _) => ZIO.logInfo(s"vector index loaded with ${knownFaces.size} known faces in ${elapsed.toSeconds}s"))
          )
          // `tocheck` runs one search per unknown face against this same, now-static set of known faces - far more searches
          // than there are vectors - so an approximate index pays for its build many times over.
          .tap(index =>
            index
              .buildApproximateIndex(HnswParams(m = 16, efConstruction = 100, efSearch = 64))
              .orDieWith(err => new RuntimeException(err.toString))
              .timed
              .flatMap((elapsed, _) => ZIO.logInfo(s"approximate (HNSW) index built in ${elapsed.toSeconds}s"))
          )
    )(index => LMDB.collectionDrop(index.collection.name).orDieWith(err => new RuntimeException(err.toString)))(use)
  }

  case class VectorIndexAcquireFailure(message: String) extends Exception(message)

  // -------------------------------------------------------------------------------------------------------------------
  val logic = ZIO.logSpan("Infer person identification from faces features and already identified faces") {
    for {
      // _              <- fixFaceWithMissingFeatures()
      // _                  <- ZIO.attemptBlocking(Thread.sleep(120.minutes)) // TODO temporary hack top be removed
      loadStarted    <- Clock.nanoTime
      knownFaces     <- featuresForIdentifiedFaces()
      unknownFaces   <- featuresForUnknowFaces()
      ignoredFaces   <- featuresForIgnoredFaces()
      loadFinished   <- Clock.nanoTime
      _              <- ZIO.logInfo(s"faces and features loaded from the database in ${(loadFinished - loadStarted) / 1000000000L}s")
      now            <- Clock.currentDateTime
      alreadyInferred = unknownFaces
                          .filter((face, _) => face.inferredIdentifiedPersonId.isDefined)
      tocheck         = unknownFaces
                          .filterNot((face, _) => face.inferredIgnore.contains(true)) // don't re-infer faces the user marked as ignored
      // .filter((face, _) => face.inferredIdentifiedPersonId.isEmpty)
      // .filter((face, _) => face.timestamp.isAfter(now.minus(20, ChronoUnit.DAYS)))
      // .filter((face, _) => face.timestamp.isAfter(now.minus(6, ChronoUnit.MONTHS)))
      personsCount                       <- MediaService.personList().runCount
      _                                  <- Console.printLine(s"$personsCount people records")
      _                                  <- Console.printLine(s"${knownFaces.size} identified and confirmed faces")
      _                                  <- Console.printLine(s"${ignoredFaces.size} ignored faces (used to veto inference)")
      _                                  <- Console.printLine(s"${unknownFaces.size} unknown faces with ${alreadyInferred.size} inferred and unconfirmed")
      knownFaceById                       = knownFaces.map((face, _) => face.faceId -> face).toMap
      searched                           <- withKnownFacesVectorIndex(knownFaces) { vectorIndex =>
                                              zio.stream.ZStream
                                                .from(tocheck)
                                                // .filter((face, _) => face.inferredIdentifiedPersonId.isEmpty) // avoid recompute, comment to force recompute
                                                // .mapZIO((face, faceFeatures) => identifyFace(vectorIndex, knownFaceById, ignoredFaces)(face, faceFeatures))
                                                // One graph walk is single-threaded, so the parallelism has to come from running several
                                                // faces at once; the LMDB writes they trigger are serialized by the database itself.
                                                .mapZIOParUnordered(searchParallelism)((face, faceFeatures) => identifyFaceWithConsensus(vectorIndex, knownFaceById, ignoredFaces)(face, faceFeatures))
                                                .filter(_ == true)
                                                .runCount
                                                .timed
                                            }
      (searchElapsed, newIdentifiedCount) = searched
      _                                  <- ZIO.logInfo(s"${tocheck.size} faces matched against the index in ${searchElapsed.toSeconds}s")
      _                                  <- Console.printLine(s"$newIdentifiedCount new faces inferred")
      _                                  <- ZIO.logInfo(s"done")
    } yield ()
  }

}
