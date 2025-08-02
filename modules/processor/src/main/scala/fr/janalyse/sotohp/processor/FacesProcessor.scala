package fr.janalyse.sotohp.processor

import zio.*
import zio.ZIOAspect.*
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.processor.model.*
import ai.djl.Application
import ai.djl.engine.Engine
import ai.djl.inference.Predictor
import ai.djl.modality.cv.Image
import ai.djl.modality.cv.ImageFactory
import ai.djl.modality.Classifications
import ai.djl.modality.cv.output.DetectedObjects
import ai.djl.repository.zoo.Criteria
import ai.djl.repository.zoo.ModelZoo
import ai.djl.repository.zoo.ZooModel
import ai.djl.training.util.ProgressBar
import ai.djl.modality.Classifications.Classification

import java.nio.file.Path
import scala.jdk.CollectionConverters.*
import wvlet.airframe.ulid.ULID

case class FacesDetectionIssue(message: String, exception: Throwable)

class FacesProcessor(facesPredictor: Predictor[Image, DetectedObjects]) extends Processor {

  override def close(): Unit = {
    facesPredictor.close()
  }
  private def makeFaceId(original: Original): FaceId = {
    // Note: up 2^80 possible values for the same millis
    FaceId(ULID.ofMillis(original.timestamp.toInstant.toEpochMilli))
  }

  private def doDetectFaces(original: Original, path: Path): List[DetectedFace] = {
    val loadedImage: Image           = ImageFactory.getInstance().fromFile(path)
    val detection: DetectedObjects   = facesPredictor.predict(loadedImage)
    val detected: List[DetectedFace] =
      detection
        .items()
        .iterator()
        .asScala
        .toList
        .asInstanceOf[List[DetectedObjects.DetectedObject]]
        .filter(_.getProbability >= 0.8d)
        .map(ob =>
          DetectedFace(
            box = BoundingBox(
              x = XAxis(ob.getBoundingBox.getBounds.getX),
              y = YAxis(ob.getBoundingBox.getBounds.getY),
              width = BoxWidth(ob.getBoundingBox.getBounds.getWidth),
              height = BoxHeight(ob.getBoundingBox.getBounds.getHeight)
            ),
            faceId = makeFaceId(original)
          )
        )

    detected
  }

  /**
   * Extracts faces detected in the image represented by the provided original instance.
   *
   * @param original The original image metadata and associated details, used to locate and analyze the image file for face detection.
   */
  private def extractFaces(original: Original) = {
    val logic = for {
      input         <- getBestInputOriginalFile(original)
      originalFaces <- ZIO
                         .attempt(doDetectFaces(original, input))
                         .mapError(th => FacesDetectionIssue("Unable to detect people faces", th))
                         .tap(faces => ZIO.log(s"found ${faces.size} faces"))
                         .logError("Faces detection issue")
                         .option
                         .map(faces => OriginalFaces(original = original, faces.isDefined, faces = faces.getOrElse(Nil)))
    } yield originalFaces
    logic
      @@ annotated("originalId" -> original.id.asString)
      @@ annotated("originalPath" -> original.mediaPath.toString)
  }

}

object FacesProcessor {
  val confThresh      = 0.85f
  val nmsThresh       = 0.45f
  val variance        = Array(0.1d, 0.2d)
  val topK            = 5000
  val scales          = Array(Array(16, 32), Array(64, 128), Array(256, 512))
  val steps           = Array(8, 16, 32)
  lazy val translator = FaceDetectionTranslator(confThresh, nmsThresh, variance, topK, scales, steps)

  def allocate(): FacesProcessor = {
    val facesCriteria: Criteria[Image, DetectedObjects] =
      Criteria
        .builder()
        .setTypes(classOf[Image], classOf[DetectedObjects])
        .optModelUrls("https://resources.djl.ai/test-models/pytorch/retinaface.zip")
        .optModelName("retinaface") // specify model file prefix
        .optTranslator(translator)
        // .optProgress(new ProgressBar())
        .optEngine("PyTorch")       // Use PyTorch engine
        .build()

    val facesModel: ZooModel[Image, DetectedObjects] = facesCriteria.loadModel()
    val facesPredictor                               = facesModel.newPredictor() // not thread safe !
    FacesProcessor(facesPredictor)
  }
}
