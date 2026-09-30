package fr.janalyse.sotohp.cli

import fr.janalyse.sotohp.search.SearchService
import fr.janalyse.sotohp.service.MediaService
import zio.*
import zio.lmdb.LMDB

/** Backfill : (re)computes the face embeddings of the existing collection with the configured face
  * features engine (`sotohp.processors.face-features.engine`). New photos get theirs automatically
  * during synchronization ; this is for switching engine/model on what is already there.
  *
  * Only the vectors are rewritten : faces, their `faceId`, boxes, crops and every identification stay
  * untouched. Resumable at no cost : an original whose faces were last embedded, successfully, by the
  * configured model is skipped, so the run can be stopped and relaunched at will.
  *
  * Args (all optional) :
  *   - `--force`           recompute even the originals already done with the configured model
  *   - `--since=YYYY`      only photos taken on/after that date (`YYYY` or `YYYY-MM-DD`)
  *   - `--limit=N`         stop after N originals actually recomputed (skipped ones don't count)
  *   - `--parallelism=N`   originals processed concurrently (default 8 : each one mostly waits on the face
  *                         recognizer's answers, and LocalAI on a GPU serves concurrent requests - measured ~20
  *                         originals/s at 8 ; lower it for a CPU-only server, which gains nothing past 2)
  *
  * If the engine fails `maxConsecutiveFailures` originals in a row (server down, model not installed),
  * the run stops : carrying on would only record failures for the rest of the collection.
  *
  * Afterwards, re-tune and re-run what derives from the vectors : `make run-face-inference-evaluate`,
  * `make run-face-inference`, `make run-face-features-clustering`.
  */
object ComputeFaceFeatures extends CommonsCLI {

  override def run =
    logic
      .provideSome[ZIOAppArgs](
        LMDB.live,
        SearchService.live,
        MediaService.live,
        Scope.default
      )

  private val reportEvery            = 100
  private val maxConsecutiveFailures = 10

  private def argValue(args: Chunk[String], name: String): Option[String] =
    args.collectFirst { case arg if arg.startsWith(s"--$name=") => arg.stripPrefix(s"--$name=") }

  case class TooManyFailures(count: Int) extends Exception(s"$count originals failed in a row - is the face features server up, and the model installed ? Stopping.")

  val logic = ZIO.logSpan("Compute face features") {
    for {
      args          <- getArgs
      force          = args.contains("--force")
      since          = argValue(args, "since").flatMap(ComputeCaptions.parseSince)
      _             <- ZIO
                         .fail(new IllegalArgumentException("Invalid --since value, expected YYYY or YYYY-MM-DD"))
                         .when(argValue(args, "since").isDefined && since.isEmpty)
      limit          = argValue(args, "limit").flatMap(_.toIntOption).filter(_ > 0)
      parallelism    = argValue(args, "parallelism").flatMap(_.toIntOption).filter(_ > 0).getOrElse(8)
      current       <- MediaService.faceFeaturesModel()
      _             <- Console.printLine(s"face features model: ${current.getOrElse("historical DJL model")}")
      _             <- Console.printLine(
                         (if (force) "recomputing every original with faces" else "recomputing the originals not yet done with this model") +
                           since.map(date => s", taken on/after ${date.toLocalDate}").getOrElse("") +
                           limit.map(count => s", at most $count").getOrElse("") +
                           s", parallelism $parallelism"
                       )
      startedAt     <- Clock.nanoTime
      doneRef       <- Ref.make(0)
      failedRef     <- Ref.make(0)
      facesRef      <- Ref.make(0)
      alreadyRef    <- Ref.make(0)
      noFaceRef     <- Ref.make(0)
      consecutive   <- Ref.make(0)
      _             <- MediaService
                         .mediaList()
                         .filter(tuple => since.forall(from => !tuple.media.timestamp.isBefore(from)))
                         // Skip before `--limit` applies, so a resumed run spends its budget on real work.
                         .filterZIO { tuple =>
                           val originalId = tuple.media.original.id
                           val keep       = for {
                             faces    <- MediaService.originalFaces(originalId).map(_.map(_.faces).getOrElse(Nil))
                             status   <- if (faces.isEmpty) ZIO.none else MediaService.originalFacesFeaturesStatus(originalId)
                             selected = faces.nonEmpty && (force || status.forall(s => !s.successful || s.model != current))
                             _        <- noFaceRef.update(_ + 1).when(faces.isEmpty)
                             _        <- alreadyRef.update(_ + 1).when(faces.nonEmpty && !selected)
                           } yield selected
                           keep
                             .catchAll(err => ZIO.logWarning(s"Couldn't check original $originalId : $err").as(false))
                         }
                         .take(limit.map(_.toLong).getOrElse(Long.MaxValue))
                         .mapZIOParUnordered(parallelism) { tuple =>
                           val originalId = tuple.media.original.id
                           MediaService
                             .faceFeaturesRecompute(originalId)
                             .map(computed => (successful = computed.status.successful, faces = computed.features.size))
                             .catchAll(err => ZIO.logWarning(s"Couldn't compute face features of $originalId : $err").as((successful = false, faces = 0)))
                         }
                         .mapZIO { result =>
                           for {
                             _      <- if (result.successful) consecutive.set(0) *> doneRef.update(_ + 1) *> facesRef.update(_ + result.faces)
                                       else failedRef.update(_ + 1) *> consecutive.update(_ + 1)
                             inARow <- consecutive.get
                             _      <- ZIO.fail(TooManyFailures(inARow)).when(inARow >= maxConsecutiveFailures)
                             done   <- doneRef.get
                             failed <- failedRef.get
                             _      <- report(done, failed, facesRef, alreadyRef, noFaceRef, startedAt).when((done + failed) % reportEvery == 0)
                           } yield ()
                         }
                         .runDrain
      done          <- doneRef.get
      failed        <- failedRef.get
      _             <- report(done, failed, facesRef, alreadyRef, noFaceRef, startedAt)
      noFace        <- noFaceRef.get
      _             <- Console.printLine(s"done - $noFace originals without any face were left alone")
      _             <- Console
                         .printLine(
                           "the vectors changed: retune with 'make run-face-inference-evaluate', then 'make run-face-inference' and 'make run-face-features-clustering'"
                         )
                         .when(done > 0)
    } yield ()
  }

  private def report(done: Int, failed: Int, facesRef: Ref[Int], alreadyRef: Ref[Int], noFaceRef: Ref[Int], startedAt: Long) =
    for {
      now     <- Clock.nanoTime
      faces   <- facesRef.get
      already <- alreadyRef.get
      noFace  <- noFaceRef.get
      seconds  = math.max(1L, (now - startedAt) / 1_000_000_000L)
      _       <- Console.printLine(f"$done originals recomputed ($faces faces embedded), $failed failed, $already already done, $noFace without faces - ${done.toDouble / seconds}%.2f originals/s")
    } yield ()
}
