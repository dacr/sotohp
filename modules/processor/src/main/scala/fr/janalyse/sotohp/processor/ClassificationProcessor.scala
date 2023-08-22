package fr.janalyse.sotohp.processor

import zio.*
import zio.ZIOAspect.*
import fr.janalyse.sotohp.store.*
import fr.janalyse.sotohp.model.*
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
import fr.janalyse.sotohp.config.{SotohpConfig, SotohpConfigIssue}
import fr.janalyse.sotohp.processor.MiniaturizeProcessor.sotophConfig

import java.nio.file.Path
import scala.jdk.CollectionConverters.*

case class ClassificationIssue(message: String, exception: Throwable)

class ClassificationProcessor(imageClassificationPredictor: Predictor[Image, Classifications]) extends Processor {
  def cleanupClassName(input: String): String =
    input.replaceAll("""^n\d+ """, "")

  def doClassifyImage(path: Path): List[String] = {
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
  }

  def classify(photo: Photo) = {
    for {
      input        <- getBestInputPhotoFile(photo)
      knownClasses <- PhotoStoreService.photoClassificationsGet(photo.source.photoId)
      classes      <- ZIO
                        .from(knownClasses)
                        .orElse(
                          ZIO
                            .attempt(doClassifyImage(input))
                            .tap(cls => ZIO.log(s"found classes : ${cls.mkString(",")}"))
                            .mapError(th => ClassificationIssue(s"Couldn't analyze photo", th))
                            .map(found => PhotoClassifications(found.map(DetectedClassification.apply)))
                        )
      _            <- PhotoStoreService
                        .photoClassificationsUpsert(photo.source.photoId, classes)
                        .when(knownClasses.isEmpty)
    } yield photo.copy(foundClassifications = Some(classes)) // TODO return updated record
  }

  /** analyse photo content using various neural networks
    *
    * @param photo
    * @return
    *   photo with updated miniatures field if some changes have occurred
    */
  def analyze(photo: Photo): ZIO[PhotoStoreService, PhotoStoreIssue | ClassificationIssue | SotohpConfigIssue, Photo] = {
    // TODO : quick, dirty & unfinished first implementation
    classify(photo)
      .logError(s"classification issue")
      .option
      .someOrElse(photo)
      @@ annotated("photoId" -> photo.source.photoId.toString())
      @@ annotated("photoPath" -> photo.source.original.path.toString)
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
