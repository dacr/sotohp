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

case class FaceFeaturesIssue(message: String)                        extends Exception(message) with CoreIssue
case class FaceFeaturesExtractIssue(message: String, err: Throwable) extends Exception(message, err) with CoreIssue

class FaceFeaturesProcessor(predictor: Predictor[Image, Array[Float]]) extends Processor {

  private def extractFaceImage(
    face: DetectedFace,
    image: BufferedImage
  ): IO[FaceFeaturesExtractIssue, BufferedImage] = {
    val x           = (face.box.x.value * image.getWidth).toInt
    val y           = (face.box.y.value * image.getHeight).toInt
    val width       = (face.box.width.value * image.getWidth).toInt
    val height      = (face.box.height.value * image.getHeight).toInt
    val fixedX      = if (x < 0) 0 else x
    val fixedY      = if (y < 0) 0 else y
    val fixedWidth  = if (fixedX + width < image.getWidth()) width else image.getWidth - fixedX
    val fixedHeight = if (fixedY + height < image.getHeight()) height else image.getHeight - fixedY

    ZIO
      .attempt(image.getSubimage(fixedX, fixedY, fixedWidth, fixedHeight))
      .mapError(err => FaceFeaturesExtractIssue(s"Couldn't extract face from image [$fixedX, $fixedY, $fixedWidth, $fixedHeight]", err))
  }

  private def extractFaceFeatures(
    face: DetectedFace,
    image: BufferedImage,
    OriginalId: OriginalId
  ): IO[CoreIssue, FaceFeatures] = {

    for {
      faceImageResized <- ZIO
                            .attempt(BasicImaging.resize(image, 160, 160))
                            .mapError(err => FaceFeaturesExtractIssue("Couldn't resize image", err))
      djlImage         <- ZIO
                            .attempt(ImageFactory.getInstance().fromImage(faceImageResized))
                            .mapError(err => FaceFeaturesExtractIssue("Couldn't load Image", err))
      features         <- ZIO
                            .attempt(predictor.predict(djlImage))
                            .mapError(err => FaceFeaturesExtractIssue("Couldn't predict face features", err))
      faceFeatures      = FaceFeatures(
                            faceId = face.faceId,
                            box = face.box,
                            features = features
                          )
    } yield faceFeatures

  }

  def extractPhotoFaceFeatures(originalFaces: OriginalFaces): IO[CoreIssue, OriginalFaceFeatures] = {
    val minRatio = 80d / 1600d // TODO use config parameter
    val logic    = for {
      image                <- loadBestInputPhoto(originalFaces.original)
      selectedFaces         = originalFaces.faces
                                .filter(face => face.box.width.value >= minRatio || face.box.height.value >= minRatio)
      selectedFaceFeatures <- ZIO
                                .foreach(selectedFaces) { face =>
                                  extractFaceFeatures(face, image, originalFaces.original.id) @@ annotated("faceId" -> face.faceId.toString())
                                }
                                .logError("Face features issue")
                                .mapError(err => FaceFeaturesIssue(s"Unable to compute face features: $err"))
                                .option
    } yield OriginalFaceFeatures(original = originalFaces.original, successful = selectedFaceFeatures.isDefined, features = selectedFaceFeatures.getOrElse(Nil))

    logic
      .logError("Face features issue")
      @@ annotated("originalId" -> originalFaces.original.id.asString)
      @@ annotated("originalPath" -> originalFaces.original.mediaPath.toString)
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
