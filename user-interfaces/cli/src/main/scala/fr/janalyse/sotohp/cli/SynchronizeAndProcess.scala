package fr.janalyse.sotohp.cli

import fr.janalyse.sotohp.core.{OriginalsStream, PhotoOperations, PhotoStream}
import fr.janalyse.sotohp.model.Photo
import fr.janalyse.sotohp.processor.{ClassificationProcessor, FaceFeaturesProcessor, FacesProcessor, MiniaturizeProcessor, NormalizeProcessor, ObjectsDetectionProcessor}
import fr.janalyse.sotohp.search.SearchService
import fr.janalyse.sotohp.store.PhotoStoreService
import zio.*
import zio.config.typesafe.*
import zio.lmdb.LMDB
import zio.logging.slf4j.bridge.Slf4jBridge

import java.nio.file.Files
import java.util.Comparator

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
      facesFeaturesProcessor    = FaceFeaturesProcessor.allocate()
      processingStream          = OriginalsStream
                                    .photoFromOriginalStream(searchRoots)
                                    .mapZIOPar(4)(normalizeAndThenMiniaturize)
                                    .mapZIO(classificationProcessor.analyze)
                                    .mapZIO(objectsDetectionProcessor.analyze)
                                    .mapZIO(facesProcessor.analyze)
                                    .mapZIO(p => facesFeaturesProcessor.extractPhotoFaceFeatures(p).as(p))
                                    .filter(_.lastSynchronized.isEmpty) // TODO it only synchronizes new photos, changes are not synchronized
                                    .grouped(100)
                                    .mapZIO(SearchService.publish)
                                    .mapZIO(updatePhotoStates)
                                    .map(_.size)
      count                    <- processingStream.runSum
      _                        <- ZIO.logInfo(s"$count photos synchronized")
      indexedPhotos             = PhotoStream.photoLazyStream()
      deletedPhotosCount       <- indexedPhotos // TODO to redesign & refactor / requires an overall transaction / to move elsewhere
                                    .filterZIO(photo => photo.photoOriginalPath.map(path => !path.toFile.exists()))
                                    .runFoldZIO(0) { case (counter, photo) =>
                                      val photoId = photo.state.photoId
                                      val result  = for {
                                        source <- photo.source.some
                                        path   <- PhotoOperations.getPhotoArtifactsCachePath(source)
                                        _      <- PhotoStoreService.photoDelete(photoId)
                                        _      <- ZIO.attempt {
                                                    Files
                                                      .walk(path)
                                                      .sorted(Comparator.reverseOrder())
                                                      .map(_.toFile)
                                                      .forEach(_.delete())
                                                  }
                                        _      <- SearchService.unpublish(photoId, photo.state.photoTimestamp)
                                      } yield ()
                                      result.as(counter + 1)
                                    }
      _                        <- ZIO.logInfo(s"${deletedPhotosCount} photos database entries removed")
      _                        <- Statistics.logic
    } yield ()
  }
}
