package fr.janalyse.sotohp.cli

import fr.janalyse.sotohp.core.OriginalsStream
import fr.janalyse.sotohp.store.PhotoStoreService
import fr.janalyse.sotohp.daemon.MiniaturizerDaemon
import zio.*
import zio.config.typesafe.*
import zio.lmdb.LMDB

object Miniaturize extends ZIOAppDefault with CommonsCLI {

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.setConfigProvider(TypesafeConfigProvider.fromTypesafeConfig(com.typesafe.config.ConfigFactory.load()))

  override def run =
    logic
      .provide(
        LMDB.liveWithDatabaseName("photos"),
        PhotoStoreService.live,
        Scope.default
      )

  val logic = ZIO.logSpan("miniaturize") {
    for {
      searchRoots <- getSearchRoots
      originals    = OriginalsStream.photoStream(searchRoots)
      _           <- MiniaturizerDaemon.miniaturizeStream(originals)
      _           <- ZIO.logInfo("Miniaturization done")
    } yield ()
  }
}
