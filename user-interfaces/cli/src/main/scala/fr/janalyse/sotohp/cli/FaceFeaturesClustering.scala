package fr.janalyse.sotohp.cli

import fr.janalyse.sotohp.core.*
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.processor.{FaceFeaturesProcessor, FacesProcessor, NormalizeProcessor}
import fr.janalyse.sotohp.store.{LazyPhoto, PhotoStoreIssue, PhotoStoreService}
import zio.*
import zio.config.typesafe.*
import zio.lmdb.LMDB

import java.io.IOException
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
  // TODO this first quick & dirty implementation is memory based !
  val logic        = ZIO.logSpan("FaceFeaturesClustering") {
    for {
      _            <- ZIO.logInfo("Face features clustering")
      faceFeatures <- PhotoStoreService
                        .photoFaceFeaturesStream()
                        .runCollect()
      data          = faceFeatures.map((faceId, faceFeatures) => faceFeatures.features.map(_.toDouble)).toArray
      clusters      = smile.clustering.DBSCAN.fit(data, 20, 0.1)
      _            <- Console.printLine(clusters.toString)
      _            <- ZIO.logInfo(s"Face features clustering done - processed ${faceFeatures.size} faces")
    } yield ()
  }
}
