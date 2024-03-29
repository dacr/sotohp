package fr.janalyse.sotohp.cli

import fr.janalyse.sotohp.core.OriginalsStream
import fr.janalyse.sotohp.processor.NormalizeProcessor
import fr.janalyse.sotohp.store.PhotoStoreService
import zio.*
import zio.config.typesafe.*
import zio.lmdb.LMDB

object Normalize extends ZIOAppDefault with CommonsCLI {

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.setConfigProvider(TypesafeConfigProvider.fromTypesafeConfig(com.typesafe.config.ConfigFactory.load()))

  override def run =
    logic
      .provide(
        LMDB.liveWithDatabaseName("photos"),
        PhotoStoreService.live,
        Scope.default
      )

  val logic = ZIO.logSpan("normalize") {
    for {
      searchRoots <- getSearchRoots
      originals    = OriginalsStream.photoFromOriginalStream(searchRoots)
      _           <- originals.mapZIO(NormalizeProcessor.normalize).runDrain
      _           <- ZIO.logInfo("Normalization done")
    } yield ()
  }
}
