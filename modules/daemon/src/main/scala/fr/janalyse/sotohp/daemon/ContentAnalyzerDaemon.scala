package fr.janalyse.sotohp.daemon

import zio.*
import zio.ZIOAspect.*

import fr.janalyse.sotohp.store.*
import fr.janalyse.sotohp.model.*

import ai.djl.Application
import ai.djl.engine.Engine
import ai.djl.modality.cv.Image
import ai.djl.modality.cv.ImageFactory
import ai.djl.modality.Classifications
import ai.djl.modality.cv.output.DetectedObjects
import ai.djl.repository.zoo.Criteria
import ai.djl.repository.zoo.ModelZoo
import ai.djl.repository.zoo.ZooModel
import ai.djl.training.util.ProgressBar
import ai.djl.modality.Classifications.Classification
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import scala.jdk.CollectionConverters._

case class AnalyzerIssue(message: String, exception: Throwable)
case class AnalyzerConfigIssue(message: String, exception: Throwable)

object ContentAnalyzerDaemon {

  lazy val objectDetectionsCriteria =
    Criteria.builder
      .optApplication(Application.CV.OBJECT_DETECTION)
      .setTypes(classOf[Image], classOf[DetectedObjects])
      .optFilter("backbone", "mobilenet1.0")
      .optProgress(new ProgressBar)
      .build

  lazy val objectDetectionsModel    = ModelZoo.loadModel(objectDetectionsCriteria)
  lazy val objectDetectionPredictor = objectDetectionsModel.newPredictor()

  def doDetectObjects(path: Path): List[String] = {
    val loadedImage: Image         = ImageFactory.getInstance().fromFile(path)
    val detection: DetectedObjects = objectDetectionPredictor.predict(loadedImage)
    val detected: List[String]     =
      detection
        .items()
        .iterator()
        .asScala
        .toList
        .asInstanceOf[List[Classifications.Classification]]
        .filter(_.getProbability >= 0.5d)
        .map(_.getClassName())
        .flatMap(_.split(""",\s+"""))
        .distinct
    detected
  }

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
      .optProgress(new ProgressBar)
      .build

  lazy val imageClassificationModel     = ModelZoo.loadModel(imageClassificationCriteria)
  lazy val imageClassificationPredictor = imageClassificationModel.newPredictor()

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

  def getInputPhotoFile(photo: Photo) = {
    ZIO
      .from(photo.normalized.map(_.path)) // faster if normalized photo is already available
      .orElse(
        ZIO
          .attempt(photo.source.original.path.toAbsolutePath)
          .mapError(th => AnalyzerIssue(s"Couldn't build input path from original photo", th))
      )
  }

  def classify(photo: Photo) = {
    for {
      input   <- getInputPhotoFile(photo)
      classes <- ZIO
                   .attempt(doClassifyImage(input))
                   .mapError(th => AnalyzerIssue(s"Couldn't analyze photo", th))
      _       <- ZIO.logInfo(s"found classes : ${classes.mkString(",")}")
    } yield photo // TODO return updated record
  }

  def detectObjects(photo: Photo) = {
    for {
      input   <- getInputPhotoFile(photo)
      objects <- ZIO
                   .attempt(doDetectObjects(input))
                   .mapError(th => AnalyzerIssue(s"Couldn't analyze photo", th))
      _       <- ZIO.logInfo(s"found objects : ${objects.mkString(",")}")
    } yield photo // TODO return updated record
  }

  /** analyse photo content using various neural networks
    *
    * @param photo
    * @return
    *   photo with updated miniatures field if some changes have occurred
    */
  def analyze(photo: Photo): ZIO[PhotoStoreService, PhotoStoreIssue | AnalyzerIssue | AnalyzerConfigIssue, Photo] = {
    // TODO : quick, dirty & unfinished first implementation
    val logic = for {
      classifiedPhoto      <- classify(photo)
      detectedObjectsPhoto <- detectObjects(classifiedPhoto)
    } yield detectedObjectsPhoto
    logic
      .logError(s"Analyze issue")
      .option
      .someOrElse(photo)
      @@ annotated("photoId" -> photo.source.photoId.toString())
      @@ annotated("photoPath" -> photo.source.original.path.toString)
  }
}