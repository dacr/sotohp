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
    (for {
      args    <- getArgs
      model   <- MediaService.faceFeaturesModel()
      profile  = FaceMatchingProfile.forModel(model).withOverrides(args)
      _       <- Console.printLine(s"face features model: ${model.getOrElse("historical DJL model")} - matching profile: $profile")
      _       <- logic(profile)
    } yield ())
      .provideSome[ZIOAppArgs](
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
                             .faceFeaturesGetCurrent(detectedFace.faceId)
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
                             enoughBig  = dimension.isEmpty || (
                                            (faceWidth.getOrElse(0d) != 0d && faceHeight.getOrElse(0d) != 0d) &&
                                              (faceWidth.getOrElse(0d) >= minimumFaceSize || faceHeight.getOrElse(0d) >= minimumFaceSize)
                                          )
                             tuple     <- MediaService
                                            .faceFeaturesGetCurrent(detectedFace.faceId)
                                            .map(feature => feature.map(detectedFace -> _))
                           } yield tuple.filter(_ => enoughBig)
                         }
    } yield featureByFace.flatten
  }

  // do not process very low resolution faces
  val minimumFaceSize = 64d

  // The distances below (maximum match distance, runner-up margin, ignored-face veto, bootstrap values) live in
  // `FaceMatchingProfile`, one set per embedding model, since a distance only means something within one model's
  // space. For the historical DJL model (`FaceMatchingProfile.legacy`) they come from `FaceInferenceEvaluate`, which
  // scores a rule against the faces a human has already confirmed. Against the previous setting (0.16, plus "the two
  // closest faces must name the same person"), 0.20 with a 0.06 runner-up margin won on every axis it measured:
  // 99.76% vs 98.73% precision on people who are in the library, 76% vs 68% of them identified at all, and - the
  // measurement that matters most for a threshold this loose - 2.36% vs 5.08% of faces belonging to someone *not* in
  // the library wrongly given a name. Raising the threshold alone would have tripled that last number; it is the
  // runner-up margin that pays for the extra reach. Lowering the margin to 0.04 trades some of the safety back for
  // reach (82% identified, 4.76% of strangers misnamed).
  //
  // Any of them can be overridden from the command line, see `FaceMatchingProfile.withOverrides`.

  // A person is scored by the mean distance of their closest few faces rather than by their single closest one, so one
  // unusually flattering photo can't win on its own.
  val facesPerPersonConsidered = 3

  // A person with this few confirmed faces gets none of that averaging - the "mean of 3" collapses to "distance to
  // whichever 1-2 faces they have", so a single so-so photo can keep them out even when they really are the match.
  // `FaceInferenceEvaluate` measured what happens if such a "bootstrapping" person is judged by a looser threshold and
  // margin instead: recall for people with exactly 2 confirmed faces nearly doubled (25% -> 43%, still 100% precision
  // on that slice), while the stranger false-positive rate barely moved (2.51% -> 2.55% over 5374 held-out faces) and
  // every better-established bucket was untouched - only the *winning* candidate's own face count ever selects these
  // looser numbers, so someone with plenty of confirmed faces is scored exactly as before whether they win outright
  // or merely out-margin a bootstrapping runner-up. A person with a single confirmed face is a separate, structurally
  // unfixable case: with nothing else of them left to compare against, no threshold can recover a match - this only
  // helps once a person has a second confirmed photo to lean on. (The measured values above are the legacy profile's
  // 0.32 / 0.03, the looser threshold/margin themselves live in `FaceMatchingProfile`.)
  val bootstrapMaxFaces = 2

  // Wide enough that several *people* show up among the candidates - a person with thousands of enrolled faces would
  // otherwise fill the whole list and leave no runner-up to compare against. `ef` matches what the evaluation used, so
  // the numbers above are the ones this actually delivers.
  val nearestCandidatesToConsider = 50
  val candidateSearchEf           = 256

  // How many unknown faces are matched against the index concurrently.
  val searchParallelism = java.lang.Runtime.getRuntime.availableProcessors()

  // Faces explicitly marked as ignored by the user. Their features are used to veto inference:
  // an unidentified face too near an ignored one gets no inferred person.
  def featuresForIgnoredFaces(): ZIO[MediaService, Exception, Chunk[(Face, FaceFeatures)]] = {
    for {
      ignoredFaces  <- MediaService.faceList().filter(_.inferredIgnore.contains(true)).runCollect
      featureByFace <- ZIO.foreach(ignoredFaces) { detectedFace =>
                         MediaService
                           .faceFeaturesGetCurrent(detectedFace.faceId)
                           .map(feature => feature.map(detectedFace -> _))
                       }
    } yield featureByFace.flatten
  }

  def mean(values: Iterable[Double]): Double = if (values.isEmpty) Double.MaxValue else values.sum / values.size

  // A face is "too near an ignored face" when its features are within the match distance of any ignored face.
  // Only ~900 entries in practice, so a plain scan stays cheap - not worth indexing.
  def isNearIgnoredFace(profile: FaceMatchingProfile, ignoredFaces: Chunk[(Face, FaceFeatures)])(face: Face, faceFeatures: FaceFeatures): Boolean = {
    ignoredFaces.exists { (ignoredFace, ignoredFeatures) =>
      ignoredFace.faceId != face.faceId && VectorMetric.Cosine.distance(faceFeatures.features, ignoredFeatures.features) <= profile.maxIgnoredMatchDistance
    }
  }

  def identifyFace(profile: FaceMatchingProfile, vectorIndex: LMDBVectorIndex[FaceId], knownFaceById: Map[FaceId, Face], ignoredFaces: Chunk[(Face, FaceFeatures)])(face: Face, faceFeatures: FaceFeatures): ZIO[MediaService, Exception, Boolean] = {
    for {
      nearest            <- vectorIndex.searchNearest(faceFeatures.features, k = 1).orDieWith(err => new RuntimeException(err.toString))
      now                <- Clock.currentDateTime
      inferredPersonId    = nearest.headOption
                              .flatMap { (nearestFaceId, foundDistance) =>
                                knownFaceById(nearestFaceId).identifiedPersonId.filter(_ => foundDistance < profile.maxMatchDistance)
                              }
                              .filterNot(_ => isNearIgnoredFace(profile, ignoredFaces)(face, faceFeatures))
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

  def identifyFaceWithConsensus(profile: FaceMatchingProfile, vectorIndex: LMDBVectorIndex[FaceId], knownFaceById: Map[FaceId, Face], ignoredFaces: Chunk[(Face, FaceFeatures)], facesPerPerson: Map[PersonId, Int])(
    face: Face,
    faceFeatures: FaceFeatures
  ): ZIO[MediaService, Exception, Boolean] = {
    for {
      nearest                                            <- vectorIndex.searchApproximate(faceFeatures.features, k = nearestCandidatesToConsider, ef = Some(candidateSearchEf)).orDieWith(err => new RuntimeException(err.toString))
      // Score each *person* among the candidates rather than each face: whoever owns the closest faces overall, not
      // whoever happens to own the single closest one. That keeps someone with thousands of enrolled photos from
      // crowding everybody else out of the shortlist purely by weight of numbers.
      rankedPersons                                       = nearest
                                                              .flatMap((faceId, distance) => knownFaceById.get(faceId).flatMap(_.identifiedPersonId).map(personId => personId -> distance))
                                                              .groupBy((personId, _) => personId)
                                                              .toVector
                                                              .map((personId, theirs) => personId -> mean(theirs.map((_, distance) => distance).sorted.take(facesPerPersonConsidered)))
                                                              .sortBy((_, distance) => distance)

      bestCandidate: Option[(id: PersonId, dist: Double)] = {
        // veto inference when the face is too near a face the user marked as ignored
        if (isNearIgnoredFace(profile, ignoredFaces)(face, faceFeatures)) None
        else
          rankedPersons.headOption.flatMap { (bestPerson, bestDistance) =>
            // A person too new to have built up `facesPerPersonConsidered` confirmed faces gets no benefit from the
            // averaging above - `bestDistance` is really just "distance to their one or two photos" - so they're
            // judged by a looser threshold/margin instead of the production one, to bootstrap them once they're
            // clearly the best match. Only the *winner*'s own face count decides this: a well-established person's
            // numbers are unaffected whether they win outright or merely out-margin a bootstrapping runner-up.
            val bootstrapping    = facesPerPerson.getOrElse(bestPerson, Int.MaxValue) <= bootstrapMaxFaces
            val effectiveMax     = if (bootstrapping) profile.bootstrapMaxMatchDistance else profile.maxMatchDistance
            val effectiveMargin  = if (bootstrapping) profile.bootstrapMinRunnerUpMargin else profile.minRunnerUpMargin
            // Only identify when the runner-up person is clearly further away. Without that gap the face is ambiguous,
            // and abstaining beats guessing - this is what keeps faces of people who aren't in the library at all from
            // being handed a name.
            val runnerUpDistance = rankedPersons.drop(1).headOption.map((_, distance) => distance).getOrElse(Double.MaxValue)
            Option.when(bestDistance <= effectiveMax && (runnerUpDistance - bestDistance) >= effectiveMargin)(bestPerson -> bestDistance)
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
              .flatMap((elapsed, _) => (ZIO.logInfo("Vector index loaded") @@ LogContext.kv("knownFacesCount" -> knownFaces.size, "elapsedSeconds" -> elapsed.toSeconds)))
          )
          // `tocheck` runs one search per unknown face against this same, now-static set of known faces - far more searches
          // than there are vectors - so an approximate index pays for its build many times over.
          .tap(index =>
            index
              .buildApproximateIndex(HnswParams(m = 16, efConstruction = 100, efSearch = 64))
              .orDieWith(err => new RuntimeException(err.toString))
              .timed
              .flatMap((elapsed, _) => (ZIO.logInfo("Approximate (HNSW) index built") @@ LogContext.kv("elapsedSeconds" -> elapsed.toSeconds)))
          )
    )(index => LMDB.collectionDrop(index.collection.name).orDieWith(err => new RuntimeException(err.toString)))(use)
  }

  case class VectorIndexAcquireFailure(message: String) extends Exception(message)

  // -------------------------------------------------------------------------------------------------------------------
  def logic(profile: FaceMatchingProfile) = ZIO.logSpan("Infer person identification from faces features and already identified faces") {
    for {
      // _              <- fixFaceWithMissingFeatures()
      // _                  <- ZIO.attemptBlocking(Thread.sleep(120.minutes)) // TODO temporary hack top be removed
      loadStarted    <- Clock.nanoTime
      knownFaces     <- featuresForIdentifiedFaces()
      unknownFaces   <- featuresForUnknowFaces()
      ignoredFaces   <- featuresForIgnoredFaces()
      loadFinished   <- Clock.nanoTime
      _              <- (ZIO.logInfo("Faces and features loaded from the database") @@ LogContext.kv("elapsedSeconds" -> (loadFinished - loadStarted) / 1000000000L))
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
      facesPerPerson                      = knownFaces.flatMap((face, _) => face.identifiedPersonId).groupBy(identity).map((personId, occurrences) => personId -> occurrences.size)
      searched                           <- withKnownFacesVectorIndex(knownFaces) { vectorIndex =>
                                              zio.stream.ZStream
                                                .from(tocheck)
                                                // .filter((face, _) => face.inferredIdentifiedPersonId.isEmpty) // avoid recompute, comment to force recompute
                                                // .mapZIO((face, faceFeatures) => identifyFace(profile, vectorIndex, knownFaceById, ignoredFaces)(face, faceFeatures))
                                                // One graph walk is single-threaded, so the parallelism has to come from running several
                                                // faces at once; the LMDB writes they trigger are serialized by the database itself.
                                                .mapZIOParUnordered(searchParallelism)((face, faceFeatures) => identifyFaceWithConsensus(profile, vectorIndex, knownFaceById, ignoredFaces, facesPerPerson)(face, faceFeatures))
                                                .filter(_ == true)
                                                .runCount
                                                .timed
                                            }
      (searchElapsed, newIdentifiedCount) = searched
      _                                  <- (ZIO.logInfo("Faces matched against the index") @@ LogContext.kv("count" -> tocheck.size, "elapsedSeconds" -> searchElapsed.toSeconds))
      _                                  <- Console.printLine(s"$newIdentifiedCount new faces inferred")
      evaluatedFaceIds                    = unknownFaces.map((face, _) => face.faceId).toSet
      clearedCount                       <- clearStaleInferences(evaluatedFaceIds)
      _                                  <- Console.printLine(s"$clearedCount stale inferences cleared (faces this run could not evaluate: no vector from the current model, or too small)")
      _                                  <- ZIO.logInfo("Done")
    } yield ()
  }

  /** Clears the inferred person of every unconfirmed face this run could not evaluate - no vector from the current
    * model (the face recognizer found no face in its crop, often a false detection, or the face is below its minimum
    * size), or below `minimumFaceSize`. Such a guess was made by an earlier run, possibly with another model, and
    * nothing supports it anymore : left alone it would sit in the review queue forever, since inference never
    * revisits that face. Only the guess goes (person, confidence, date) : `inferredIgnore` is the user's own decision
    * and stays, and confirmed identifications are never touched.
    */
  def clearStaleInferences(evaluatedFaceIds: Set[FaceId]): ZIO[MediaService, Exception, Int] =
    for {
      // collected first : the faces collection is not walked while it is being written to
      stale <- MediaService
                 .faceList()
                 .filter(face => face.identifiedPersonId.isEmpty && face.inferredIdentifiedPersonId.isDefined && !evaluatedFaceIds.contains(face.faceId))
                 .runCollect
      _     <- ZIO.foreachDiscard(stale) { face =>
                 MediaService.faceUpdate(
                   face.faceId,
                   face.copy(inferredIdentifiedPersonId = None, inferredIdentifiedPersonConfidence = None, inferredTimestamp = None)
                 )
               }
    } yield stale.size

}
