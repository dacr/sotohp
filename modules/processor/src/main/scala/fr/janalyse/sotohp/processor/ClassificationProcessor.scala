package fr.janalyse.sotohp.processor

import ai.djl.Application
import ai.djl.inference.Predictor
import ai.djl.modality.Classifications
import ai.djl.modality.Classifications.Classification
import ai.djl.modality.cv.{Image, ImageFactory}
import ai.djl.repository.zoo.{Criteria, ModelZoo}
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.core.*
import fr.janalyse.sotohp.processor.model.*
import zio.*
import zio.ZIOAspect.*

import java.nio.file.Path
import scala.jdk.CollectionConverters.*

case class ClassificationIssue(message: String, exception: Throwable) extends Exception(message, exception) with CoreIssue

class ClassificationProcessor(imageClassificationPredictor: Predictor[Image, Classifications]) extends Processor {
  private def cleanupClassName(input: String): String =
    input.replaceAll("""^n\d+ """, "")

  private def doClassifyImage(path: Path): List[DetectedClassification] = {
    val img = ImageFactory.getInstance().fromFile(path)

    val found: Classifications = imageClassificationPredictor.predict(img)
    found
      .items()
      .asScala
      .toList
      .asInstanceOf[List[Classification]]
      .filter(_.getProbability >= 0.5d)
      .map(_.getClassName)
      .map(cleanupClassName)
      .flatMap(_.split(""",\s+"""))
      .distinct
      .map(DetectedClassification.apply)
  }

  /** analyse photo content using various neural networks
    *
    * @param original
    * @return
    *   originalClassifications photo with updated miniatures field if some changes have occurred
    */
  def classify(original: Original) = {
    val logic = for {
      input           <- getBestInputOriginalFile(original)
      classifications <- ZIO
                           .attempt(doClassifyImage(input))
                           .mapError(th => ObjectsDetectionIssue("Unable to compute classifications", th))
                           .tap(cls => ZIO.log(s"found classes : ${cls.mkString(",")}"))
                           .logError("Classification issue")
                           .option
                           .map(mayBeClasses => OriginalClassifications(original, mayBeClasses.isDefined, mayBeClasses.getOrElse(Nil)))
    } yield classifications

    logic
      @@ annotated("originalId" -> original.id.asString)
      @@ annotated("originalPath" -> original.mediaPath.toString)

  }
}

object ClassificationProcessor {

  lazy val imageClassificationCriteria =
    Criteria.builder
      .optApplication(Application.CV.IMAGE_CLASSIFICATION)
      .setTypes(classOf[Image], classOf[Classifications])
      // ------------------------------------
      // .optFilter("flavor","v1")
      // .optFilter("dataset","cifar10")
      // ------------------------------------
      // .optFilter("flavor","v3_large")
      // .optFilter("dataset","imagenet")
      // ------------------------------------
      .optFilter("flavor", "v1d")
      .optFilter("dataset", "imagenet")
      // .optProgress(new ProgressBar)
      .build

  lazy val imageClassificationModel = ModelZoo.loadModel(imageClassificationCriteria)

  def allocate(): ClassificationProcessor = {
    val imageClassificationPredictor = imageClassificationModel.newPredictor() // not thread safe !
    ClassificationProcessor(imageClassificationPredictor)
  }
}
