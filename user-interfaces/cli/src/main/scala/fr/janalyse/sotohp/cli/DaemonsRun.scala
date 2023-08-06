package fr.janalyse.sotohp.cli

import fr.janalyse.sotohp.core.OriginalsStream
import fr.janalyse.sotohp.daemon.MiniaturizerDaemon
import fr.janalyse.sotohp.store.PhotoStoreService
import zio.*
import zio.config.typesafe.*
import zio.lmdb.LMDB

object DaemonsRun extends ZIOAppDefault with CommonsCLI {

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.setConfigProvider(TypesafeConfigProvider.fromTypesafeConfig(com.typesafe.config.ConfigFactory.load()))

  override def run =
    logic
      .provide(
        LMDB.liveWithDatabaseName("photos"),
        PhotoStoreService.live,
        Scope.default
      )

  val logic = for {
    _                 <- ZIO.logInfo("photos background operations")
    miniaturizerFiber <- Miniaturize.logic.fork
    normalizerFiber   <- Normalize.logic.fork
    _                 <- miniaturizerFiber.join
    _                 <- normalizerFiber.join
  } yield ()
}
