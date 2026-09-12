package fr.janalyse.sotohp.cli

import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.search.{SearchService, SearchServiceConfig}
import fr.janalyse.sotohp.service.{MediaService, MediaTuple}
import zio.*
import zio.lmdb.LMDB

import java.time.{LocalDate, OffsetDateTime, ZoneOffset}
import scala.util.Try

/** One-shot backfill of the image-to-text captions ("auto descriptions"). New photos are captioned
  * automatically during `synchronizeStart` once the captioner is enabled; this command is for the
  * existing collection.
  *
  * Requires `sotohp.processors.captioner.enabled = true` and a reachable Ollama server with a
  * vision model pulled (`ollama pull qwen2.5vl:3b`). When the captioner is disabled every photo is
  * a fast no-op and nothing is stored.
  *
  * Captioning is slow - a vision model spends seconds per photo - so the whole collection is a
  * multi-day run, and stopping and relaunching has to be cheap. By default a photo the model has
  * already been run on is skipped outright, whether it produced a caption or not: it costs one
  * LMDB read, no GPU time, and no search-engine write.
  *
  * Selection modes, from narrowest to widest, and they combine (a photo is picked up as soon as
  * any one of them wants it):
  *
  *   - default                  only photos the model has never seen
  *   - `--retry`                those, plus the ones it saw and got nothing usable from - the flag
  *                              to reach for after fixing a bad model or prompt, since it re-tries
  *                              the failures without paying to redo the captions that already worked
  *   - `--recompute-before=TS`  those, plus every photo whose caption - successful or not - was
  *                              computed before the given instant, regardless of how it turned out.
  *                              The flag for "I changed the model/prompt at TS, redo everything
  *                              captioned before that, leave what I've done since alone". `TS` is a
  *                              full ISO-8601 offset date-time, e.g. `2026-09-12T19:04:24+02:00` -
  *                              a `+02` offset with no minutes (as our own log lines occasionally
  *                              render it) is also accepted, so a value copied straight from the
  *                              log works as-is
  *   - `--force`                every selected photo, overwriting captions that already succeeded
  *
  * `--force` wins over the others when several are given. The rest narrow which photos are
  * considered at all:
  *
  *   - `--starred`      only starred photos
  *   - `--since=YYYY`   only photos taken on/after that date (`YYYY` or `YYYY-MM-DD`) - the photo's
  *                      own shoot date, unrelated to `--recompute-before`'s caption timestamp
  *   - `--limit=N`      stop after N photos have actually been captioned - photos skipped because
  *                      they were already done do not count against it, so the flag means the same
  *                      thing on a fresh run and on a resume
  *   - `--no-index`     don't touch the search engine (a later `make run-search-reindex` then has
  *                      to publish the new descriptions)
  *
  * Photos are visited oldest first, so `--limit` alone takes the oldest N; combine it with
  * `--since` to target recent ones.
  *
  * Each photo this run actually captions has its `SaoMedia` document re-published to Elasticsearch
  * straight away, so a multi-day run makes its descriptions searchable as it goes instead of
  * leaving the index stale until the end. Photos it skipped are left alone - their documents are
  * already in step. Indexing failures are counted and logged, never fatal: the caption is safe in
  * LMDB and `make run-search-reindex` can always catch up.
  */
object ComputeCaptions extends CommonsCLI {

  override def run =
    logic
      .provideSome[ZIOAppArgs](
        LMDB.live,
        SearchService.live,
        MediaService.live,
        Scope.default
      )

  // Ollama serializes generation per model, so extra concurrency only fills its queue; a little
  // still helps by overlapping image load/downscale with the model's work.
  val parallelism = math.max(1, math.min(4, java.lang.Runtime.getRuntime.availableProcessors() / 2))

  // How often progress (rate + ETA) is reported.
  private val reportEvery = 50

  private def argValue(args: Chunk[String], name: String): Option[String] =
    args.collectFirst { case arg if arg.startsWith(s"--$name=") => arg.stripPrefix(s"--$name=") }

  /** `YYYY` or `YYYY-MM-DD`, interpreted at the start of the day, UTC. */
  private[cli] def parseSince(raw: String): Option[OffsetDateTime] = {
    val trimmed = raw.trim
    val asYear  = Try(trimmed.toInt).toOption.filter(y => y >= 1826 && y <= 9999)
    asYear
      .map(year => OffsetDateTime.of(year, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC))
      .orElse(Try(LocalDate.parse(trimmed).atStartOfDay().atOffset(ZoneOffset.UTC)).toOption)
  }

  /** Full ISO-8601 offset date-time, e.g. `2026-09-12T19:04:24.825477913+02:00`. A two-digit
    * offset with no minutes (`+02`) is also accepted - our own log lines occasionally render it
    * that way - so a timestamp copied straight out of a log line parses as-is.
    */
  private[cli] def parseInstant(raw: String): Option[OffsetDateTime] = {
    val trimmed = raw.trim
    def attempt(s: String) = Try(OffsetDateTime.parse(s)).toOption
    attempt(trimmed).orElse(attempt(trimmed.replaceAll("([+-]\\d{2})$", "$1:00")))
  }

  private def selectionDescription(
    force: Boolean,
    retry: Boolean,
    recomputeBefore: Option[OffsetDateTime],
    starredOnly: Boolean,
    since: Option[OffsetDateTime],
    limit: Option[Int]
  ): String = {
    val what =
      if (force) "Recaptioning every selected photo"
      else
        List(
          Some("Captioning photos the model has not seen yet"),
          Option.when(retry)("retrying the ones that failed"),
          recomputeBefore.map(cutoff => s"recomputing everything captioned before $cutoff")
        ).flatten.mkString(", ")
    val where = List(
      Option.when(starredOnly)("starred only"),
      since.map(date => s"taken on/after ${date.toLocalDate}"),
      limit.map(count => s"at most $count photos")
    ).flatten
    if (where.isEmpty) s"$what - the whole collection" else s"$what - ${where.mkString(", ")}"
  }

  private def selected(tuple: MediaTuple, starredOnly: Boolean, since: Option[OffsetDateTime]): Boolean = {
    val starredOk = !starredOnly || tuple.media.starred.value
    val sinceOk   = since.forall(from => !tuple.media.timestamp.isBefore(from))
    starredOk && sinceOk
  }

  private def reportProgress(captioned: Int, failed: Int, skipped: Int, indexed: Int, indexing: Boolean, startedAtMillis: Long, total: Option[Int]): UIO[Unit] =
    Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS).flatMap { now =>
      val done           = captioned + failed
      val elapsedSeconds = math.max(1L, (now - startedAtMillis) / 1000L)
      val perPhoto       = elapsedSeconds.toDouble / math.max(1, done)
      val eta            = total.map(count => f" - eta ${(count - done) * perPhoto / 3600}%.1fh")
      val indexedPart    = if (indexing) s", $indexed indexed" else ""
      Console
        .printLine(f"$captioned captioned, $failed failed, $skipped skipped$indexedPart - $perPhoto%.1fs/photo${eta.getOrElse("")}")
        .orDie
    }

  val logic = ZIO.logSpan("Compute image-to-text captions") {
    for {
      args            <- getArgs
      force            = args.contains("--force")
      retry            = args.contains("--retry")
      starredOnly      = args.contains("--starred")
      noIndex          = args.contains("--no-index")
      since            = argValue(args, "since").flatMap(parseSince)
      badSince         = argValue(args, "since").isDefined && since.isEmpty
      recomputeBefore  = argValue(args, "recompute-before").flatMap(parseInstant)
      badRecomputeBefore = argValue(args, "recompute-before").isDefined && recomputeBefore.isEmpty
      limit            = argValue(args, "limit").flatMap(_.toIntOption).filter(_ > 0)
      _               <- ZIO
                           .fail(new IllegalArgumentException(s"Invalid --since value, expected YYYY or YYYY-MM-DD"))
                           .when(badSince)
      _               <- ZIO
                           .fail(new IllegalArgumentException(s"Invalid --recompute-before value, expected a full ISO-8601 offset date-time"))
                           .when(badRecomputeBefore)
      searchEnabled   <- SearchServiceConfig.config.map(_.enabled)
      indexing         = !noIndex && searchEnabled
      total           <- MediaService.originalCount()
      _               <- Console.printLine(s"$total photos in the collection")
      _               <- Console.printLine(selectionDescription(force, retry, recomputeBefore, starredOnly, since, limit))
      _              <- Console.printLine(
                          if (indexing) "Search index updated as photos are captioned"
                          else if (noIndex) "Search index left untouched (--no-index)"
                          else "Search index left untouched (search engine disabled in configuration)"
                        )
      startedAt      <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
      captionedRef   <- Ref.make(0)
      doneRef        <- Ref.make(0)
      skippedRef     <- Ref.make(0)
      indexedRef     <- Ref.make(0)
      indexFailedRef <- Ref.make(0)
      _              <- MediaService
                          .mediaList()
                          .filter(tuple => selected(tuple, starredOnly, since))
                          // Drop the already-attempted photos *before* `--limit` is applied, so a
                          // resumed run spends its budget on real work rather than on skipping. A
                          // photo redone by a `--recompute-before` pass is stored with today's
                          // timestamp, so a later run with the same cutoff naturally leaves it
                          // alone - no separate bookkeeping needed.
                          .filterZIO { tuple =>
                            if (force) ZIO.succeed(true)
                            else
                              MediaService
                                .originalCaptionStatus(tuple.media.original.id)
                                .map {
                                  case None         => true // never attempted -> always
                                  case Some(status) =>
                                    (retry && !status.successful) ||
                                      recomputeBefore.exists(cutoff => status.timestamp.isBefore(cutoff))
                                }
                                .catchAll(_ => ZIO.succeed(true))
                                .tap(keep => skippedRef.update(_ + 1).unless(keep))
                          }
                          .take(limit.getOrElse(Int.MaxValue))
                          .mapZIOParUnordered(parallelism) { tuple =>
                            val originalId = tuple.media.original.id
                            val bagName    = tuple.media.bag.map(_.name.text).getOrElse("(no bag)")
                            for {
//                              _            <- ZIO.logInfo(s"captioning [$bagName] ${tuple.media.timestamp}")
                              // Recompute (rather than the cheaper "fill if missing") whenever the
                              // filter above may have selected a photo that already has a record:
                              // --force, --retry and --recompute-before can all match one.
                              mayBeCaption <- (if (force || retry || recomputeBefore.isDefined)
                                                 MediaService.originalCaptionRecompute(originalId).map(_.caption)
                                               else MediaService.originalCaption(originalId).map(_.flatMap(_.caption)))
                                                .catchAll(_ => ZIO.succeed(None))
                              ok            = mayBeCaption.isDefined
                              _            <- ZIO.logInfo(
                                                s"captioned [$bagName] ${tuple.media.timestamp} -> " +
                                                  mayBeCaption.getOrElse("(rejected - no usable caption)")
                                              )
                              // Only photos this run put through the model can have changed their
                              // document; a rejected recaption is published too, so an earlier
                              // description is not left stranded in the index.
                              _         <- MediaService
                                             .searchPublish(originalId)
                                             .foldZIO(
                                               err => indexFailedRef.update(_ + 1) *> ZIO.logWarning(s"search publish failed for $originalId : $err"),
                                               _ => indexedRef.update(_ + 1)
                                             )
                                             .when(indexing)
                              captioned <- captionedRef.updateAndGet(count => if (ok) count + 1 else count)
                              // One atomic counter drives the reporting cadence, so concurrent
                              // fibers can neither both trigger a report nor both miss one.
                              done      <- doneRef.updateAndGet(_ + 1)
                              skipped   <- skippedRef.get
                              indexed   <- indexedRef.get
                              _         <- reportProgress(captioned, done - captioned, skipped, indexed, indexing, startedAt, limit)
                                             .when(done % reportEvery == 0)
                            } yield ()
                          }
                          .runDrain
      captioned      <- captionedRef.get
      done           <- doneRef.get
      skipped        <- skippedRef.get
      indexed        <- indexedRef.get
      indexFailed    <- indexFailedRef.get
      _              <- Console.printLine(s"done - $captioned captioned, ${done - captioned} failed, $skipped skipped as already done")
      _              <- Console.printLine(s"       $indexed search documents updated, $indexFailed failed").when(indexing)
      _              <- Console
                          .printLine("run 'make run-search-reindex' to index the new descriptions")
                          .when(captioned > 0 && (!indexing || indexFailed > 0))
    } yield ()
  }

}
