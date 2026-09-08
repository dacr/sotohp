package fr.janalyse.sotohp.cli

import fr.janalyse.sotohp.search.SearchService
import fr.janalyse.sotohp.service.MediaService
import zio.*
import zio.lmdb.LMDB

/** One-shot backfill: deduce the textual place (town / region / country) for every located photo
  * that does not have one yet, by offline reverse-geocoding of its effective GPS position. New
  * photos get theirs automatically during `synchronizeStart` (see
  * `MediaServiceLive.placeResolution`); this command is for the existing collection.
  *
  *   - `--force` recomputes even when a `deductedPlace` is already stored (needed after a GeoNames
  *     dump refresh or a bulk location change). A user-defined place is always left untouched.
  *
  * The GeoNames dump must be in place first (`make download-geonames`); without it every photo is
  * simply visited and left unchanged.
  *
  * The search documents carry the place, so run `make run-search-reindex` afterwards to make it
  * searchable on the existing collection (`make run-reindex` only rebuilds the LMDB indexes).
  */
object ComputePlaces extends CommonsCLI {

  override def run =
    logic
      .provideSome[ZIOAppArgs](
        LMDB.live,
        SearchService.live,
        MediaService.live,
        Scope.default
      )

  // The resolver is immutable and read-only once loaded, so it is safe to hammer in parallel.
  val parallelism = math.max(1, java.lang.Runtime.getRuntime.availableProcessors())

  val logic = ZIO.logSpan("Deduce textual places from GPS") {
    for {
      args      <- getArgs
      force      = args.contains("--force")
      total     <- MediaService.originalCount()
      _         <- Console.printLine(s"$total photos in the collection")
      _         <- Console.printLine(
                     if (force) "Recomputing deducted places even when already stored"
                     else "Deducing only the missing places"
                   )
      processed <- MediaService
                     .mediaList()
                     .mapZIOParUnordered(parallelism) { tuple =>
                       val originalId = tuple.media.original.id
                       (if (force) MediaService.placeRecompute(originalId)
                        else MediaService.placeResolve(originalId)).ignoreLogged.as(1)
                     }
                     .runSum
      _         <- Console.printLine(s"done - $processed photos visited")
      _         <- Console
                     .printLine("search documents carry the place - refresh Elasticsearch with 'make run-search-reindex'")
                     .when(processed > 0)
    } yield ()
  }

}
