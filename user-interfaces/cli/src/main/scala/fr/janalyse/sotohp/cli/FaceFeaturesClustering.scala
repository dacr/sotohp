package fr.janalyse.sotohp.cli

import fr.janalyse.sotohp.core.*
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.processor.{BasicImaging, FaceFeaturesProcessor, FacesProcessor, NormalizeProcessor}
import fr.janalyse.sotohp.store.{LazyPhoto, PhotoStoreIssue, PhotoStoreService}
import smile.math.distance.EuclideanDistance
import zio.*
import zio.config.typesafe.*
import zio.lmdb.LMDB

import java.io.IOException
import java.nio.file.Path
import java.time.{Instant, OffsetDateTime}
import scala.io.AnsiColor.*

object FaceFeaturesClustering extends ZIOAppDefault with CommonsCLI {

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.setConfigProvider(TypesafeConfigProvider.fromTypesafeConfig(com.typesafe.config.ConfigFactory.load()))

  override def run                      =
    logic
      .provide(
        LMDB.liveWithDatabaseName("photos"),
        PhotoStoreService.live,
        Scope.default
      )
  // TODO this first quick & dirty implementation is memory based !
  import smile.plot.*
  import smile.plot.swing.*
  implicit val renderer: Canvas => Unit = JWindow.apply

  val logic = ZIO.logSpan("FaceFeaturesClustering") {
    for {
      _       <- ZIO.logInfo("Face features clustering")
      faces   <- PhotoStoreService
                   .photoFaceFeaturesStream()
                   .runCollect()
      data     = faces.map((faceId, faceFeatures) => faceFeatures.features.map(_.toDouble)).toArray
      // clusters      = smile.clustering.DeterministicAnnealing.fit(data, 200)
      // clusters      = smile.clustering.DENCLUE.fit(data, 1.0d, 50) // NOTE : doesn't work well with high dimensional data
      // clusters      = smile.clustering.SpectralClustering.fit(data, 100, 0.2d) // NOTE : number of clusters must be given !

      // clusters      = smile.clustering.DBSCAN.fit(data, 20, 0.2) // NOTE : bad results probably because of too many dimensions in data
      clusters      = smile.clustering.MEC.fit(data, new EuclideanDistance(), 5000, 0.005) // NOTE : number of clusters must be given as a hint - start with larger value
      // clusters = smile.clustering.CLARANS.fit(data, new EuclideanDistance(), 3000, 5) // NOTE : number of clusters must be given as a hint - start with larger value
      // _             = show(plot(data, clusters.y, '.')) // Invalid bound dimension: 512
      _       <- Console.printLine(clusters.toString)
      _       <- ZIO.foreachDiscard(faces.zip(clusters.y))({ case ((faceId, faceFeatures), clusterIndex) =>
                   extractFaceToCluster(faceId, faceFeatures, clusterIndex).ignoreLogged
                 })
      _       <- ZIO.logInfo(s"Face features clustering done - processed ${faces.size} faces using ${clusters.getClass.getName}")
    } yield ()
  }

  def extractFaceToCluster(faceId: FaceId, faceFeatures: FaceFeatures, clusterIndex: Int) = {
    val destPath = Path.of("clusters", s"cluster-$clusterIndex")
    if (!destPath.toFile.exists()) destPath.toFile.mkdirs()
    for {
      originalId  <- PhotoStoreService.photoStateGet(faceFeatures.photoId).some.map(_.originalId)
      photoSource <- PhotoStoreService.photoSourceGet(originalId).some
      originalPath = photoSource.original.path
      image       <- ZIO.attempt(BasicImaging.load(originalPath))
      x            = (faceFeatures.box.x * image.getWidth).toInt
      y            = (faceFeatures.box.y * image.getHeight).toInt
      w            = (faceFeatures.box.width * image.getWidth).toInt
      h            = (faceFeatures.box.height * image.getHeight).toInt
      faceImage   <- ZIO.attempt(image.getSubimage(x, y, w, h))
      _           <- ZIO.attempt(BasicImaging.save(Path.of(destPath.toString, s"${faceId.toString()}.png"), faceImage, None))
    } yield ()
  }
}
