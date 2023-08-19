package fr.janalyse.sotohp.cli

import fr.janalyse.sotohp.core.OriginalsStream
import fr.janalyse.sotohp.processor.{MiniaturizeProcessor, NormalizeProcessor, ContentAnalyzerProcessor}
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
      _               <- ZIO.logInfo("start photos synchronization and processing")
      searchRoots     <- getSearchRoots
      processingStream = OriginalsStream
                           .photoStream(searchRoots)
                           .mapZIOParUnordered(4)(NormalizeProcessor.normalize)
                           .mapZIOParUnordered(4)(MiniaturizeProcessor.miniaturize)
                           //.map(ContentAnalyzerDaemon.analyze)
      count           <- processingStream.runCount
      _               <- ZIO.logInfo(s"found $count photos")
    } yield ()
  }
}
