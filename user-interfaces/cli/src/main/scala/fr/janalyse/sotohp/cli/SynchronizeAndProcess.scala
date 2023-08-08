package fr.janalyse.sotohp.cli

import fr.janalyse.sotohp.core.OriginalsStream
import fr.janalyse.sotohp.daemon.{MiniaturizerDaemon, NormalizerDaemon}
import fr.janalyse.sotohp.store.PhotoStoreService
import zio.*
import zio.config.typesafe.*
import zio.lmdb.LMDB

object SynchronizeAndProcess extends ZIOAppDefault with CommonsCLI {

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.setConfigProvider(TypesafeConfigProvider.fromTypesafeConfig(com.typesafe.config.ConfigFactory.load()))

  override def run =
    logic
      .provide(
        LMDB.liveWithDatabaseName("photos"),
        PhotoStoreService.live,
        Scope.default
      )

  val logic = ZIO.logSpan("synchronizeAndProcess") {
    for {
      _                  <- ZIO.logInfo("start photos synchronization and processing")
      miniaturizerConfig <- MiniaturizerDaemon.miniaturizerConfig
      normalizerConfig   <- NormalizerDaemon.normalizerConfig
      searchRoots        <- getSearchRoots
      processingStream    = OriginalsStream.photoStream(searchRoots).mapZIOParUnordered(4) { photo =>
                              for {
                                _ <- MiniaturizerDaemon.buildMiniatures(photo, miniaturizerConfig).ignore
                                _ <- NormalizerDaemon.buildNormalizedPhoto(photo, normalizerConfig).ignore
                              } yield ()
                            }
      count              <- processingStream.runCount
      _                  <- ZIO.logInfo(s"found $count photos")
    } yield ()
  }
}
