package fr.janalyse.sotohp.cli

import fr.janalyse.sotohp.core.OriginalsStream
import fr.janalyse.sotohp.model.Photo
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

  def normalizeAndThenMiniaturize(photo: Photo) =
    for {
      p1 <- NormalizeProcessor.normalize(photo) // always before miniaturize phase
      p2 <- MiniaturizeProcessor.miniaturize(p1)
    } yield p2

  def updatePhotoStates(photos: Chunk[Photo]) = {
    ZIO.foreach(photos)(photo =>
      Clock.currentDateTime.flatMap(timestamp =>
        PhotoStoreService
          .photoStateUpdate(photo.source.photoId, s => s.copy(lastSynchronized = Some(timestamp)))
          .as(photo)
      )
    )
  }
  
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
                                    .mapZIOPar(4)(normalizeAndThenMiniaturize)
                                    .mapZIO(classificationProcessor.analyze)
                                    .mapZIO(objectsDetectionProcessor.analyze)
                                    .mapZIO(facesProcessor.analyze)
                                    .filter(_.lastSynchronized.isEmpty)
                                    .grouped(500)
                                    .mapZIO(SearchService.publish)
                                    .mapZIO(updatePhotoStates)
                                    .map(_.size)
      count                    <- processingStream.runSum
      _                        <- ZIO.logInfo(s"$count photos synchronized")
    } yield ()
  }
}
