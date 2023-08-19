package fr.janalyse.sotohp.cli

import fr.janalyse.sotohp.cli.Miniaturize.getSearchRoots
import fr.janalyse.sotohp.core.OriginalsStream
import fr.janalyse.sotohp.processor.ContentAnalyzerProcessor
import fr.janalyse.sotohp.store.PhotoStoreService
import zio.{Runtime, Scope, ZIO, ZIOAppArgs, ZIOAppDefault, ZLayer}
import zio.config.typesafe.TypesafeConfigProvider
import zio.lmdb.LMDB

object Analyze extends ZIOAppDefault with CommonsCLI {

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.setConfigProvider(TypesafeConfigProvider.fromTypesafeConfig(com.typesafe.config.ConfigFactory.load()))

  override def run =
    logic
      .provide(
        LMDB.liveWithDatabaseName("photos"),
        PhotoStoreService.live,
        Scope.default
      )

  val logic = ZIO.logSpan("analyze") {
    for {
      searchRoots <- getSearchRoots
      originals    = OriginalsStream.photoStream(searchRoots)
      _           <- originals.mapZIO(ContentAnalyzerProcessor.analyze).runDrain
      _           <- ZIO.logInfo("Miniaturization done")
    } yield ()
  }
}
