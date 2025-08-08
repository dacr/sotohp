package fr.janalyse.sotohp.cli
//
//import fr.janalyse.sotohp.core.*
//import fr.janalyse.sotohp.model.*
//import fr.janalyse.sotohp.processor.{FaceFeaturesProcessor, FacesProcessor, NormalizeProcessor}
//import fr.janalyse.sotohp.store.{LazyPhoto, PhotoStoreIssue, PhotoStoreService}
//import zio.*
//import zio.config.typesafe.*
//import zio.lmdb.LMDB
//
//import java.io.IOException
//import java.time.{Instant, OffsetDateTime}
//import scala.io.AnsiColor.*
//
//object FaceFeatures extends ZIOAppDefault with CommonsCLI {
//
//  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
//    Runtime.setConfigProvider(TypesafeConfigProvider.fromTypesafeConfig(com.typesafe.config.ConfigFactory.load()))
//
//  override def run =
//    logic
//      .provide(
//        LMDB.liveWithDatabaseName("photos"),
//        PhotoStoreService.live,
//        Scope.default
//      )
//
//  val logic = ZIO.logSpan("FaceFeatures") {
//    val photoStream           = PhotoStream.photoStream()
//    val initialState          = QuickFixState()
//    val faceFeaturesProcessor = FaceFeaturesProcessor.allocate()
//    for {
//      _     <- ZIO.logInfo("Face features processing")
//      count <- photoStream
//                 .mapZIO(photo => faceFeaturesProcessor.extractPhotoFaceFeatures(photo))
//                 .runSum
//      _     <- ZIO.logInfo(s"Face features processing done - processed $count faces")
//    } yield ()
//  }
//}
