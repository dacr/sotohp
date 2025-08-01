package fr.janalyse.sotohp.processor

import ai.djl.inference.Predictor
import ai.djl.modality.cv.{Image, ImageFactory}
import ai.djl.repository.zoo.{Criteria, ZooModel}
import fr.janalyse.sotohp.config.*
import fr.janalyse.sotohp.media.imaging.BasicImaging
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.store.PhotoStoreService
import zio.*
import zio.ZIOAspect.*

import java.awt.image.BufferedImage

case class FaceFeaturesIssue(message: String, exception: Throwable) extends Exception(message, exception)

class FaceFeaturesProcessor(predictor: Predictor[Image, Array[Float]]) extends Processor {

  private def extractFaceImage(
    face: DetectedFace,
    lazyImage: ZIO[Any, Any, BufferedImage]
  ): ZIO[Any, Any, BufferedImage] = {
    for {
      image      <- lazyImage
      x           = (face.box.x * image.getWidth).toInt
      y           = (face.box.y * image.getHeight).toInt
      width       = (face.box.width * image.getWidth).toInt
      height      = (face.box.height * image.getHeight).toInt
      fixedX      = if (x < 0) 0 else x
      fixedY      = if (y < 0) 0 else y
      fixedWidth  = if (fixedX + width < image.getWidth()) width else image.getWidth - fixedX
      fixedHeight = if (fixedY + height < image.getHeight()) height else image.getHeight - fixedY
      faceImage  <- ZIO
                      .attempt(image.getSubimage(fixedX, fixedY, fixedWidth, fixedHeight))
                      .mapError(err => FaceFeaturesIssue(s"Couldn't extract face from image [$fixedX, $fixedY, $fixedWidth, $fixedHeight]", err))
    } yield faceImage
  }

  private def extractFaceFeatures(
    face: DetectedFace,
    lazyImage: ZIO[Any, Any, BufferedImage],
    photoId: PhotoId
  ) = {

    val faceFeatureExtract = for {
      faceImage        <- extractFaceImage(face, lazyImage)
      faceImageResized <- ZIO
                            .attempt(BasicImaging.resize(faceImage, 160, 160))
                            .mapError(err => FaceFeaturesIssue("Couldn't resize image", err))
      djlImage         <- ZIO
                            .attempt(ImageFactory.getInstance().fromImage(faceImageResized))
                            .mapError(err => FaceFeaturesIssue("Couldn't load Image", err))
      features         <- ZIO
                            .attempt(predictor.predict(djlImage))
                            .mapError(err => FaceFeaturesIssue("Couldn't predict face features", err))
      faceFeatures      = FaceFeatures(
                            photoId = photoId,
                            someoneId = None,
                            box = face.box,
                            features = features
                          )
      _                <- PhotoStoreService
                            .photoFaceFeaturesUpsert(face.faceId, faceFeatures)
                            .mapError(err => FaceFeaturesIssue("Couldn't upsert face features for face", err))
    } yield ()

    for {
      foundFaceFeatures <- PhotoStoreService
                             .photoFaceFeaturesGet(face.faceId)
      _                 <- faceFeatureExtract
                             .when(foundFaceFeatures.isEmpty)
    } yield ()
  }

  def extractPhotoFaceFeatures(photo: Photo): RIO[PhotoStoreService, Int] = {
    val logic = for {
      config       <- SotohpConfig.zioConfig
      minRatio      = 80d / 1600d // TODO use config parameter
      lazyImage    <- loadBestInputPhoto(photo).memoize
      event         = photo.description.flatMap(_.event).map(_.text).getOrElse("no-event-given")
      selectedFaces = photo.foundFaces
                        .map(_.faces)
                        .getOrElse(Nil)
                        .filter(face => face.box.width >= minRatio || face.box.height >= minRatio)
      _            <- ZIO.foreachDiscard(selectedFaces) { face =>
                        extractFaceFeatures(face, lazyImage, photo.source.photoId)
                          @@ annotated("faceId" -> face.faceId.toString())
                      }
    } yield selectedFaces.size

    logic
      .logError("Face features issue")
      .option
      .someOrElse(0)
      @@ annotated("photoId" -> photo.source.photoId.toString())
      @@ annotated("photoPath" -> photo.source.original.path.toString)
  }

}

object FaceFeaturesProcessor {
  def allocate(): FaceFeaturesProcessor = {
    val criteria: Criteria[Image, Array[Float]] =
      Criteria
        .builder()
        .setTypes(classOf[Image], classOf[Array[Float]])
        .optModelUrls("https://resources.djl.ai/test-models/pytorch/face_feature.zip")
        .optModelName("face_feature") // specify model file prefix
        .optTranslator(new FeatureExtraction.FaceFeatureTranslator())
        // .optProgress(new ProgressBar())
        .optEngine("PyTorch")         // Use PyTorch engine
        .build()

    val model: ZooModel[Image, Array[Float]] = criteria.loadModel()
    val predictor                            = model.newPredictor() // not thread safe !
    FaceFeaturesProcessor(predictor)
  }

}
