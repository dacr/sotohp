package fr.janalyse.sotohp.processor

import ai.djl.inference.Predictor
import ai.djl.modality.cv.{Image, ImageFactory}
import ai.djl.repository.zoo.{Criteria, ZooModel}
import fr.janalyse.sotohp.core.CoreIssue
import fr.janalyse.sotohp.media.imaging.BasicImaging
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.processor.model.*
import zio.*
import zio.ZIOAspect.*

import java.awt.image.BufferedImage
trait FaceFeaturesIssue(message: String, mayByErr: Option[Throwable]) extends Exception with CoreIssue
case class FaceFeaturesGeneralIssue(message: String)                  extends FaceFeaturesIssue(message, None)
case class FaceFeaturesExtractIssue(message: String, err: Throwable)  extends FaceFeaturesIssue(message, Some(err))

class FaceFeaturesProcessor(predictor: Predictor[Image, Array[Float]]) extends Processor {

  override def close(): Unit = {
    predictor.close()
  }

  private def extractFaceImage(
    face: DetectedFace,
    originalImage: BufferedImage
  ): IO[FaceFeaturesExtractIssue, BufferedImage] = {
    val x           = (face.box.x.value * originalImage.getWidth).toInt
    val y           = (face.box.y.value * originalImage.getHeight).toInt
    val width       = (face.box.width.value * originalImage.getWidth).toInt
    val height      = (face.box.height.value * originalImage.getHeight).toInt
    val fixedX      = if (x < 0) 0 else x
    val fixedY      = if (y < 0) 0 else y
    val fixedWidth  = if (fixedX + width < originalImage.getWidth()) width else originalImage.getWidth - fixedX
    val fixedHeight = if (fixedY + height < originalImage.getHeight()) height else originalImage.getHeight - fixedY

    ZIO
      .attempt(originalImage.getSubimage(fixedX, fixedY, fixedWidth, fixedHeight))
      .mapError(err => FaceFeaturesExtractIssue(s"Couldn't extract face from image [$fixedX, $fixedY, $fixedWidth, $fixedHeight]", err))
  }

  private def extractFaceFeatures(
    face: DetectedFace,
    originalImage: BufferedImage
  ): IO[CoreIssue, FaceFeatures] = {

    for {
      faceImage        <- extractFaceImage(face, originalImage)
      faceImageResized <- ZIO
                            .attempt(BasicImaging.resize(faceImage, 160, 160))
                            .mapError(err => FaceFeaturesExtractIssue("Couldn't resize face image", err))
      djlImage         <- ZIO
                            .attempt(ImageFactory.getInstance().fromImage(faceImageResized))
                            .mapError(err => FaceFeaturesExtractIssue("Couldn't load face Image", err))
      features         <- ZIO
                            .attempt(predictor.predict(djlImage))
                            .mapError(err => FaceFeaturesExtractIssue("Couldn't predict face features", err))
      faceFeatures      = FaceFeatures(
                            faceId = face.faceId,
                            features = features
                          )
    } yield faceFeatures

  }

  /** Extracts features from faces detected in the input `OriginalFaces` object while applying filtering and processing.
    *
    * @param faces
    *   the `OriginalFaces` object containing the original image and the list of detected faces to process
    * @return
    *   an `IO` effect resulting in either a `CoreIssue` (in case of an error) or an `OriginalFaceFeatures` containing the processed face features
    */
  def extractFaceFeatures(faces: OriginalFaces): IO[CoreIssue, OriginalFaceFeatures] = {
    val minRatio = 80d / 1600d // TODO use config parameter
    val logic    = for {
      now               <- Clock.currentDateTime
      originalImage     <- loadOriginalBestInputFileForProcessors(faces.original)
      selectedFaces      = faces.faces
                             .filter(face => face.box.width.value >= minRatio || face.box.height.value >= minRatio)
      mayBeFaceFeatures <- ZIO
                             .foreach(selectedFaces) { face =>
                               extractFaceFeatures(face, originalImage) @@ annotated("faceId" -> face.faceId.toString)
                             }
                             .logError("Face features issue")
                             .mapError(err => FaceFeaturesGeneralIssue(s"Unable to compute face features: $err"))
                             .option
      status             = ProcessedStatus(successful = mayBeFaceFeatures.isDefined, timestamp = now)
      features           = mayBeFaceFeatures.getOrElse(Nil)
    } yield OriginalFaceFeatures(faces.original, status, features)

    logic
      .logError("Face features issue")
      @@ annotated("originalId" -> faces.original.id.asString)
      @@ annotated("originalPath" -> faces.original.absoluteMediaPath.toString)
  }

}

object FaceFeaturesProcessor {
  def allocate(): IO[FaceFeaturesExtractIssue, FaceFeaturesProcessor] = {
    for {
      semaphore <- Semaphore.make(1)
      logic      = ZIO
                     .attemptBlocking {
                       val criteria: Criteria[Image, Array[Float]] =
                         Criteria
                           .builder()
                           .setTypes(classOf[Image], classOf[Array[Float]])
                           .optModelUrls("https://resources.djl.ai/test-models/pytorch/face_feature.zip")
                           .optModelName("face_feature") // specify model file prefix
                           .optTranslator(new FeatureExtraction.FaceFeatureTranslator())
                           // .optProgress(new ProgressBar())
                           .optEngine("PyTorch")
                           // Use PyTorch engine
                           .build()

                       val model: ZooModel[Image, Array[Float]] = criteria.loadModel()
                       val predictor                            = model.newPredictor() // not thread safe !
                       FaceFeaturesProcessor(predictor)
                     }
                     .logError("Face features processor allocation issue")
                     .mapError(err => FaceFeaturesExtractIssue("Unable to allocate face features processor", err))
      result    <- semaphore.withPermit(logic)
    } yield result
  }

}
