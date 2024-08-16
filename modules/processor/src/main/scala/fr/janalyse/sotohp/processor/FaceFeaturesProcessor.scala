package fr.janalyse.sotohp.processor

import zio.*
import zio.ZIOAspect.*
import zio.config.*
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.core.*
import fr.janalyse.sotohp.store.{PhotoStoreIssue, PhotoStoreService}
import fr.janalyse.sotohp.config.*
import ai.djl.ModelException
import ai.djl.inference.Predictor
import ai.djl.modality.cv.Image
import ai.djl.modality.cv.ImageFactory
import ai.djl.modality.cv.transform.Normalize
import ai.djl.modality.cv.transform.ToTensor
import ai.djl.ndarray.NDArray
import ai.djl.ndarray.NDList
import ai.djl.repository.zoo.Criteria
import ai.djl.repository.zoo.ZooModel
import ai.djl.training.util.ProgressBar
import ai.djl.translate.Pipeline
import ai.djl.translate.TranslateException
import ai.djl.translate.Translator
import ai.djl.translate.TranslatorContext

import java.awt.image.{BufferedImage, DataBufferByte}

case class FaceFeaturesIssue(message: String, exception: Throwable) extends Exception(message, exception)

class FaceFeaturesProcessor(predictor: Predictor[Image, Array[Float]]) extends Processor {

  def extractFaceImage(
    face: DetectedFace,
    lazyImage: ZIO[Any, FaceFeaturesIssue, BufferedImage]
  ): ZIO[Any, FaceFeaturesIssue, BufferedImage] = {
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

  def extractFaceFeatures(
    face: DetectedFace,
    lazyImage: ZIO[Any, FaceFeaturesIssue, BufferedImage],
    photoId: PhotoId
  ): ZIO[PhotoStoreService, FaceFeaturesIssue, Unit] = {

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
                            .mapError(err => FaceFeaturesIssue(s"Couldn't upsert face features for face ${face.faceId}", err))
    } yield ()

    for {
      foundFaceFeatures <- PhotoStoreService
                             .photoFaceFeaturesGet(face.faceId)
                             .mapError(err => FaceFeaturesIssue(s"Couldn't get face features for face ${face.faceId}", err))
      _                 <- faceFeatureExtract
                             .when(foundFaceFeatures.isEmpty)
    } yield ()
  }

  def extractPhotoFaceFeatures(photo: Photo): ZIO[PhotoStoreService, PhotoStoreIssue | FaceFeaturesIssue | ProcessorIssue | SotohpConfigIssue, Int] = {
    val logic = for {
      config       <- sotophConfig
      minRatio      = 80d / 1600d // TODO use config parameter
      lazyImage    <- loadBestInputPhoto(photo)
                        .mapError(err => FaceFeaturesIssue(s"Couldn't load image ${photo.source.photoId} for face features extraction purposes", err))
                        .memoize
      event         = photo.description.flatMap(_.event).map(_.text).getOrElse("no-event-given")
      selectedFaces = photo.foundFaces
                        .map(_.faces)
                        .getOrElse(Nil)
                        .filter(face => face.box.width >= minRatio || face.box.height >= minRatio)
      // _            <- ZIO.logInfo(s"Processing ${event} ${photo.source.photoId}")
      _            <- ZIO.foreachDiscard(selectedFaces)(face => extractFaceFeatures(face, lazyImage, photo.source.photoId))
    } yield selectedFaces.size

    logic
      .logError(s"Face features issue")
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
