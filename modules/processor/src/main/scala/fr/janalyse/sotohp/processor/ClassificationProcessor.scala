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

  override def close(): Unit = {
    imageClassificationPredictor.close()
  }

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
      .map(ob => (cleanupClassName(ob.getClassName), ob.getProbability))
      .flatMap((name, prob) => name.split(""",\s+""").toList.map(_ -> prob))
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
      now          <- Clock.currentDateTime
      input        <- getOriginalBestInputFileForProcessors(original)
      mayBeClasses <- ZIO
                        .attempt(doClassifyImage(input))
                        .mapError(th => ClassificationIssue("Unable to compute classifications", th))
                        .tap(cls => ZIO.log(s"found classes : ${cls.mkString(",")}"))
                        .logError("Classification issue")
                        .option
      status        = ProcessedStatus(successful = mayBeClasses.isDefined, timestamp = now)
      classes       = mayBeClasses.getOrElse(Nil)
    } yield OriginalClassifications(original, status, classes)

    logic
      @@ annotated("originalId" -> original.id.asString)
      @@ annotated("originalPath" -> original.absoluteMediaPath.toString)

  }
}

object ClassificationProcessor {

  def base = Criteria.builder
    .optApplication(Application.CV.IMAGE_CLASSIFICATION)
    .setTypes(classOf[Image], classOf[Classifications])

  lazy val imageClassificationCriteria = {
    // ------------------------------------
    // .optFilter("flavor","v1")
    // .optFilter("dataset","cifar10")
    // ------------------------------------
    // .optFilter("flavor","v3_large")
    // .optFilter("dataset","imagenet")
    // ------------------------------------
    // base.optFilter("flavor", "v1d").optFilter("dataset", "imagenet").build
    // base.optModelUrls("djl://ai.djl.mxnet/inceptionv3").build
    // base.optModelUrls("djl://ai.djl.mxnet/darknet").build
    // base.optModelUrls("djl://ai.djl.mxnet/senet").build
    // base.optModelUrls("djl://ai.djl.mxnet/mobilenet").optFilter("flavor", "v2").optFilter("multiplier", "0.5").build
    // base.optModelUrls("djl://ai.djl.mxnet/resnet").optFilter("layers", "101").optFilter("dataset","imagenet").build

    base.optFilter("layers", "101").optFilter("dataset", "imagenet").build
    // base.optModelUrls("djl://ai.djl.mxnet/se_resnext").optFilter("flavor", "64x4d").build
    // base.optModelUrls("djl://ai.djl.mxnet/resnet").optFilter("layers", "152").build
  }

  lazy val imageClassificationModel = ModelZoo.loadModel(imageClassificationCriteria)

  def allocate(): IO[ClassificationIssue, ClassificationProcessor] = {
    for {
      semaphore <- Semaphore.make(1)
      logic      = ZIO
                     .attemptBlocking(ClassificationProcessor(imageClassificationModel.newPredictor() /* not thread safe !*/ ))
                     .mapError(ClassificationIssue("Unable to allocate classification processor", _))
      result    <- semaphore.withPermit(logic)
    } yield result
  }
}
