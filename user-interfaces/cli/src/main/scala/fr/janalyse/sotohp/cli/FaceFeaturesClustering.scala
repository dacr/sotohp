package fr.janalyse.sotohp.cli

import fr.janalyse.sotohp.config.SotohpConfig
import fr.janalyse.sotohp.core.*
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.processor.{BasicImaging, FaceFeaturesProcessor, FacesProcessor, NormalizeProcessor}
import fr.janalyse.sotohp.store.{LazyPhoto, PhotoStoreIssue, PhotoStoreService}
import smile.math.distance.{EuclideanDistance, Metric}
import smile.util.SparseArray
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

  override def run =
    logic
      .provide(
        LMDB.liveWithDatabaseName("photos"),
        PhotoStoreService.live,
        Scope.default
      )

  class SimilarityDistance extends Metric[Array[Double]] {
    override def d(feature1: Array[Double], feature2: Array[Double]): Double = {
      var ret    = 0.0d
      var mod1   = 0.0d
      var mod2   = 0.0d
      val length = feature1.length
      var i      = 0
      while (i < length) {
        ret = ret + feature1(i) * feature2(i)
        mod1 = mod1 + feature1(i) * feature1(i)
        mod2 = mod2 + feature2(i) * feature2(i)
        i += 1
      }
      (ret / Math.sqrt(mod1) / Math.sqrt(mod2) + 1d) / 2.0d
    }
  }

  // TODO this first quick & dirty implementation is memory based !
  import smile.plot.*
  import smile.plot.swing.*
  implicit val renderer: Canvas => Unit = JWindow.apply

  val logic = ZIO.logSpan("FaceFeaturesClustering") {
    for {
      _     <- ZIO.logInfo("Face features clustering")
      faces <- PhotoStoreService
                 .photoFaceFeaturesStream()
                 .runCollect()
      k      = 300
      // alpha    = 0.8
      data   = faces.map((faceId, faceFeatures) => faceFeatures.features.map(_.toDouble)).toArray
      // clusters      = smile.clustering.xmeans(data, k)
      // clusters = smile.clustering.specc(data, k, 200, 0.2d) // NOTE : number of clusters must be given !
      // clusters = smile.clustering.dbscan(data, 20, 0.2) // NOTE : bad results probably because of too many dimensions in data
      // clusters = smile.clustering.mec(data, new EuclideanDistance(), k, 0.001) // NOTE : number of clusters must be given as a hint - start with larger value

      clusters = smile.clustering.clarans(data, new EuclideanDistance(), k, 50) // NOTE : number of clusters must be given as a hint - start with larger value
      // clusters = smile.clustering.clarans(data, SimilarityDistancep(), k, 50) // NOTE : number of clusters must be given as a hint - start with larger value

      // clusters = smile.clustering.dac(data, k = 500, alpha = 0.9)
      // clusters = smile.clustering.dac(data, k = k, alpha = alpha)
      // _             = show(plot(data, clusters.y, '.')) // Invalid bound dimension: 512
      algo     = clusters.getClass.getName.split("[.]").last
      _       <- Console.printLine(clusters.toString)
      _       <- ZIO.logInfo(s"Face features clustering done - processed ${faces.size} faces using $algo")
      _       <- ZIO.foreachDiscard(faces.zip(clusters.y))({ case ((faceId, faceFeatures), clusterIndex) =>
                   extractFaceToCluster(faceId, faceFeatures, clusterIndex, s"$algo-$k").ignoreLogged
                 })
    } yield ()
  }

  def extractFaceToCluster(faceId: FaceId, faceFeatures: FaceFeatures, clusterIndex: Int, algo: String) = {
    val destPath = Path.of(s"clusters-$algo", s"cluster-$clusterIndex")
    if (!destPath.toFile.exists()) destPath.toFile.mkdirs()
    for {
      config <- ZIO.config(SotohpConfig.config)

      originalId     <- PhotoStoreService.photoStateGet(faceFeatures.photoId).some.map(_.originalId)
      photoSource    <- PhotoStoreService.photoSourceGet(originalId).some
      normalizedPath <- ZIO.attempt(PhotoOperations.makeNormalizedFilePath(photoSource, config)) // faster because lighter
      // imagePath       = photoSource.original.path // May require post rotation
      imagePath       = normalizedPath                                                           // This one is already rotated
      image          <- ZIO.attempt(BasicImaging.load(imagePath))
      x               = (faceFeatures.box.x * image.getWidth).toInt
      y               = (faceFeatures.box.y * image.getHeight).toInt
      w               = (faceFeatures.box.width * image.getWidth).toInt
      h               = (faceFeatures.box.height * image.getHeight).toInt
      nx              = if (x < 0) 0 else x
      ny              = if (y < 0) 0 else y
      nw              = if (nx + w < image.getWidth()) w else image.getWidth - nx
      nh              = if (ny + h < image.getHeight()) h else image.getHeight - ny
      faceImage      <- ZIO.attempt(image.getSubimage(nx, ny, nw, nh))
      _              <- ZIO.attempt(BasicImaging.save(Path.of(destPath.toString, s"${faceId.toString()}.png"), faceImage, None))
    } yield ()
  }
}
