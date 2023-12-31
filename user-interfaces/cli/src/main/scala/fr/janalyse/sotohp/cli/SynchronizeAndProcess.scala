package fr.janalyse.sotohp.cli

import fr.janalyse.sotohp.core.OriginalsStream
import fr.janalyse.sotohp.processor.{ClassificationProcessor, FacesProcessor, MiniaturizeProcessor, NormalizeProcessor, ObjectsDetectionProcessor}
import fr.janalyse.sotohp.search.SearchService
import fr.janalyse.sotohp.store.PhotoStoreService
import zio.*
import zio.config.typesafe.*
import zio.lmdb.LMDB
import zio.logging.slf4j.bridge.Slf4jBridge

object SynchronizeAndProcess extends ZIOAppDefault with CommonsCLI {

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.setConfigProvider(TypesafeConfigProvider.fromTypesafeConfig(com.typesafe.config.ConfigFactory.load()))

  override def run =
    logic
      .provide(
        LMDB.liveWithDatabaseName("photos"),
        PhotoStoreService.live,
        SearchService.live,
        Scope.default,
        Slf4jBridge.initialize
      )

  val logic = ZIO.logSpan("synchronizeAndProcess") {
    for {
      _                        <- ZIO.logInfo("start photos synchronization and processing")
      searchRoots              <- getSearchRoots
      _                        <- ZIO.logInfo(s"Defined search Roots ${searchRoots.map(_.baseDirectory).mkString(",")}")
      classificationProcessor   = ClassificationProcessor.allocate()
      objectsDetectionProcessor = ObjectsDetectionProcessor.allocate()
      facesProcessor            = FacesProcessor.allocate()
      processingStream          = OriginalsStream
                                    .photoFromOriginalStream(searchRoots)
                                    .mapZIOParUnordered(4)(NormalizeProcessor.normalize) // First ! as normalized photos will accelerate next steps
                                    .mapZIOParUnordered(4)(MiniaturizeProcessor.miniaturize)
                                    .mapZIO(classificationProcessor.analyze)
                                    .mapZIO(objectsDetectionProcessor.analyze)
                                    .mapZIO(facesProcessor.analyze)
                                    .grouped(500)
                                    .mapZIO(photos => SearchService.publish(photos))
                                    .map(_.size)
      count                    <- processingStream.runSum
      _                        <- ZIO.logInfo(s"found $count photos")
    } yield ()
  }
}
