package fr.janalyse.sotohp.cli

import fr.janalyse.sotohp.service.MediaService
import fr.janalyse.sotohp.search.SearchService
import zio.*
import zio.lmdb.LMDB

object Reindex extends CommonsCLI {

  override def run =
    logic
      .provide(
        LMDB.live,
        SearchService.live,
        MediaService.live,
        Scope.default
      )

  val logic = ZIO.logSpan("Reindex") {
    for {
      _ <- ZIO.logInfo("Starting full index rebuild...")
      _ <- MediaService.reindexAll()
      _ <- ZIO.logInfo("Index rebuild completed successfully.")
    } yield ()
  }
}
