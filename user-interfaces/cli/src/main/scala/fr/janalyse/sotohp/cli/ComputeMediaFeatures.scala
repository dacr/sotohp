package fr.janalyse.sotohp.cli

import fr.janalyse.sotohp.model.Media
import fr.janalyse.sotohp.search.SearchService
import fr.janalyse.sotohp.service.MediaService
import zio.*
import zio.lmdb.LMDB

/** One-shot backfill: compute the whole-image feature vector (embedding) for every photo that does
  * not have one yet. New photos get theirs automatically during `synchronizeStart` (see
  * `MediaServiceLive.synchronizeProcessors`); this command is for the existing collection.
  *
  * Two flags cover recomputing vectors that already exist, which the default pass deliberately
  * leaves alone :
  *   - `--force` recomputes even when one is stored.
  *   - `--rotated-only` restricts the pass to media whose effective rotation differs from their
  *     original's EXIF one - the photos the user turned by hand.
  *
  * Combined (`--force --rotated-only`) they are the repair for vectors computed before
  * `computeMediaFeatures` learnt to embed the photo at its effective rotation : those, and only
  * those, describe a sideways image. Rebuild the clusters afterwards
  * (`make run-media-features-clustering`), since they are derived from these vectors.
  */
object ComputeMediaFeatures extends CommonsCLI {

  override def run =
    logic
      .provideSome[ZIOAppArgs](
        LMDB.live,
        SearchService.live,
        MediaService.live,
        Scope.default
      )

  // The DJL predictor is not thread-safe and is shared, so keep this modest.
  val parallelism = math.max(1, java.lang.Runtime.getRuntime.availableProcessors() / 2)

  private def hasUserCustomRotation(media: Media): Boolean = {
    val originalRotation  = media.original.orientation.map(_.rotationDegrees).getOrElse(0)
    val effectiveRotation = media.orientation.orElse(media.original.orientation).map(_.rotationDegrees).getOrElse(0)
    media.orientation.isDefined && originalRotation != effectiveRotation
  }

  val logic = ZIO.logSpan("Compute whole-image feature vectors") {
    for {
      args        <- getArgs
      force        = args.contains("--force")
      rotatedOnly  = args.contains("--rotated-only")
      total       <- MediaService.originalCount()
      _           <- Console.printLine(s"$total photos in the collection")
      _           <- Console.printLine(
                       (if (force) "Recomputing vectors even when already stored" else "Computing only the missing vectors") +
                         (if (rotatedOnly) ", restricted to user-rotated photos" else "")
                     )
      processed   <- MediaService
                       .mediaList()
                       .filter(tuple => !rotatedOnly || hasUserCustomRotation(tuple.media))
                       .mapZIOParUnordered(parallelism) { tuple =>
                         val originalId = tuple.media.original.id
                         (if (force) MediaService.mediaFeaturesRecompute(originalId).unit
                          else MediaService.originalMediaFeatures(originalId).unit).ignoreLogged
                           .as(1)
                       }
                       .runSum
      _           <- Console.printLine(s"done - $processed photos visited")
      _           <- Console
                       .printLine("clusters are derived from these vectors - rebuild them with 'make run-media-features-clustering'")
                       .when(force && processed > 0)
    } yield ()
  }

}
