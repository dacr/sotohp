package fr.janalyse.sotohp.cli

import fr.janalyse.sotohp.search.SearchService
import fr.janalyse.sotohp.service.MediaService
import zio.*
import zio.lmdb.LMDB

/** One-shot backfill: compute the whole-image feature vector (embedding) for every
  * photo that does not have one yet. New photos get theirs automatically during
  * `synchronizeStart` (see `MediaServiceLive.synchronizeProcessors`); this command is
  * for the existing collection.
  */
object ComputeMediaFeatures extends CommonsCLI {

  override def run =
    logic
      .provide(
        LMDB.live,
        SearchService.live,
        MediaService.live,
        Scope.default
      )

  // The DJL predictor is not thread-safe and is shared, so keep this modest.
  val parallelism = math.max(1, java.lang.Runtime.getRuntime.availableProcessors() / 2)

  val logic = ZIO.logSpan("Compute whole-image feature vectors") {
    for {
      total     <- MediaService.originalCount()
      _         <- Console.printLine(s"$total photos to check")
      processed <- MediaService
                     .originalList()
                     .mapZIOParUnordered(parallelism)(original =>
                       MediaService
                         .originalMediaFeatures(original.id)
                         .ignoreLogged
                         .as(1)
                     )
                     .runSum
      _         <- Console.printLine(s"done - $processed photos visited")
    } yield ()
  }

}
