package fr.janalyse.sotohp.cli

import fr.janalyse.sotohp.search.SearchService
import fr.janalyse.sotohp.service.MediaService
import zio.*
import zio.lmdb.LMDB

/** One-shot backfill of the image-to-text captions ("auto descriptions"). New photos are
  * captioned automatically during `synchronizeStart` once the captioner is enabled; this command
  * is for the existing collection.
  *
  * Requires `sotohp.processors.captioner.enabled = true` and a reachable Ollama server with a
  * vision model pulled (`ollama pull moondream`). When the captioner is disabled every photo is
  * a fast no-op and nothing is stored.
  *
  *   - default: caption only photos with no successful caption yet (missing, or a prior failure).
  *   - `--force`: recaption every photo, overwriting stored captions.
  *
  * Afterwards run `make run-search-reindex` so the new text reaches the search index.
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

  // Ollama serializes generation per model anyway; a little concurrency still helps with the
  // image encode + HTTP round trip. Kept low on purpose.
  val parallelism = math.max(1, math.min(4, java.lang.Runtime.getRuntime.availableProcessors() / 2))

  val logic = ZIO.logSpan("Compute image-to-text captions") {
    for {
      args      <- getArgs
      force      = args.contains("--force")
      total     <- MediaService.originalCount()
      _         <- Console.printLine(s"$total photos in the collection")
      _         <- Console.printLine(if (force) "Recaptioning every photo" else "Captioning only photos without a successful caption")
      captioned <- MediaService
                     .originalList()
                     .mapZIOParUnordered(parallelism) { original =>
                       (if (force) MediaService.originalCaptionRecompute(original.id).map(_.status.successful)
                        else MediaService.originalCaption(original.id).map(_.exists(_.status.successful)))
                         .catchAll(_ => ZIO.succeed(false))
                         .map(if (_) 1 else 0)
                     }
                     .runSum
      _         <- Console.printLine(s"done - $captioned photos captioned")
      _         <- Console.printLine("run 'make run-search-reindex' to index the new descriptions").when(captioned > 0)
    } yield ()
  }

}
