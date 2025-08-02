package fr.janalyse.sotohp.processor

import ai.djl.Application
import ai.djl.inference.Predictor
import ai.djl.modality.Classifications
import ai.djl.modality.cv.{Image, ImageFactory}
import ai.djl.modality.cv.output.DetectedObjects
import ai.djl.repository.zoo.{Criteria, ModelZoo}
import fr.janalyse.sotohp.core.CoreIssue
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.processor.model.*
import zio.*
import zio.ZIOAspect.*

import java.nio.file.Path
import scala.jdk.CollectionConverters.*

case class ObjectsDetectionIssue(message: String, exception: Throwable) extends Exception(message, exception) with CoreIssue

class ObjectsDetectionProcessor(objectDetectionPredictor: Predictor[Image, DetectedObjects]) extends Processor {
  def doDetectObjects(path: Path): List[DetectedObject] = {
    val loadedImage: Image             = ImageFactory.getInstance().fromFile(path)
    val detection: DetectedObjects     = objectDetectionPredictor.predict(loadedImage)
    val detected: List[DetectedObject] =
      detection
        .items()
        .iterator()
        .asScala
        .toList
        .asInstanceOf[List[DetectedObjects.DetectedObject]]
        .filter(_.getProbability >= 0.5d)
        .map(ob =>
          DetectedObject(
            name = ob.getClassName.trim,
            box = BoundingBox(
              x = XAxis(ob.getBoundingBox.getBounds.getX),
              y = YAxis(ob.getBoundingBox.getBounds.getY),
              width = BoxWidth(ob.getBoundingBox.getBounds.getWidth),
              height = BoxHeight(ob.getBoundingBox.getBounds.getHeight)
            )
          )
        )

    detected
  }


  /**
   * Extracts detected objects from the given original image using an object detection mechanism.
   *
   * @param original The original image from which objects need to be detected. This contains various metadata 
   *                 about the image such as its path, size, and additional attributes.
   * @return An `IO` computation that either results in a `CoreIssue` in case of failure or produces an 
   *         `OriginalDetectedObjects` instance which encapsulates the detection results including a flag 
   *         indicating success and the list of detected objects.
   */
  def extractObjects(original: Original): IO[CoreIssue, OriginalDetectedObjects] = {
    val logic = for {
      input        <- getBestInputOriginalFile(original)
      mayBeObjects <- ZIO
                        .attempt(doDetectObjects(input))
                        .mapError(th => ObjectsDetectionIssue("Unable to recognize objects", th))
                        .tap(objs => ZIO.log(s"found objects : ${objs.mkString(",")}"))
                        .logError("Objects detection issue")
                        .option
    } yield OriginalDetectedObjects(original, mayBeObjects.isDefined, mayBeObjects.getOrElse(List.empty))

    logic
      @@ annotated("originalId" -> original.id.asString)
      @@ annotated("originalPath" -> original.mediaPath.toString)
  }

}

object ObjectsDetectionProcessor {
  lazy val objectDetectionsCriteria =
    Criteria.builder
      .optApplication(Application.CV.OBJECT_DETECTION)
      .setTypes(classOf[Image], classOf[DetectedObjects])
      .optFilters(Map("backbone" -> "mobilenet1.0").asJava)
      // .optProgress(new ProgressBar)
      .build

  lazy val objectDetectionsModel = ModelZoo.loadModel(objectDetectionsCriteria)

  def allocate(): ObjectsDetectionProcessor = {
    val objectsDetectionPredictor = objectDetectionsModel.newPredictor() // not thread safe !
    ObjectsDetectionProcessor(objectsDetectionPredictor)
  }
}
