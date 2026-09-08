package fr.janalyse.sotohp.cli

import fr.janalyse.sotohp.service.MediaService
import fr.janalyse.sotohp.search.SearchService
import zio.*
import zio.lmdb.LMDB

/** Re-publishes every stored media to the search engine.
  *
  * The normal synchronization only ever pushes never-synced medias, so this is the way to refresh
  * Elasticsearch after a `SaoMedia` schema change or a bulk enrichment backfill - e.g. after
  * `make run-compute-places`, which fills `deductedPlace` on already-synced photos.
  *
  * `make run-reindex` rebuilds the LMDB indexes only and is unaffected by this.
  */
object SearchReindex extends CommonsCLI {

  override def run =
    logic
      .provide(
        LMDB.live,
        SearchService.live,
        MediaService.live,
        Scope.default
      )

  val logic = ZIO.logSpan("SearchReindex") {
    for {
      _     <- ZIO.logInfo("Clearing the search indexes, then re-publishing every media...")
      count <- MediaService.searchReindexAll()
      _     <- ZIO.logInfo(s"Search reindex completed - $count medias published.")
    } yield ()
  }
}
