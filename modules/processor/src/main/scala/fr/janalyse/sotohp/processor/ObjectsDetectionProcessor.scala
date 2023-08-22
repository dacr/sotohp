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

case class ObjectsDetectionIssue(message: String, exception: Throwable)

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
              x = ob.getBoundingBox.getBounds.getX,
              y = ob.getBoundingBox.getBounds.getY,
              width = ob.getBoundingBox.getBounds.getWidth,
              height = ob.getBoundingBox.getBounds.getHeight
            )
          )
        )

    detected
  }

  def detectObjects(photo: Photo) = {
    for {
      input       <- getBestInputPhotoFile(photo)
      // knownObjects <- PhotoStoreService.photoObjectsGet(photo.source.photoId)
      knownObjects = None
      objects     <- ZIO
                       .from(knownObjects)
                       .orElse(
                         ZIO
                           .attempt(doDetectObjects(input))
                           .tap(objs => ZIO.log(s"found objects : ${objs.mkString(",")}"))
                           .mapError(th => ObjectsDetectionIssue(s"Couldn't analyze photo", th))
                           .map(detectedObjects => PhotoObjects(objects = detectedObjects))
                       )
      _           <- PhotoStoreService
                       .photoObjectsUpsert(photo.source.photoId, objects)
                       .when(knownObjects.isEmpty)
    } yield photo.copy(foundObjects = Some(objects))
  }

  /** analyse photo content using various neural networks
    *
    * @param photo
    * @return
    *   photo with updated miniatures field if some changes have occurred
    */
  def analyze(photo: Photo): ZIO[PhotoStoreService, PhotoStoreIssue | ObjectsDetectionIssue | SotohpConfigIssue, Photo] = {
    // TODO : quick, dirty & unfinished first implementation
    detectObjects(photo)
      .logError("Objects detection issue")
      .option
      .someOrElse(photo)
      @@ annotated("photoId" -> photo.source.photoId.toString())
      @@ annotated("photoPath" -> photo.source.original.path.toString)
  }
}

object ObjectsDetectionProcessor {
  lazy val objectDetectionsCriteria =
    Criteria.builder
      .optApplication(Application.CV.OBJECT_DETECTION)
      .setTypes(classOf[Image], classOf[DetectedObjects])
      .optFilter("backbone", "mobilenet1.0")
      // .optProgress(new ProgressBar)
      .build

  lazy val objectDetectionsModel = ModelZoo.loadModel(objectDetectionsCriteria)

  def allocate(): ObjectsDetectionProcessor = {
    val objectsDetectionPredictor = objectDetectionsModel.newPredictor() // not thread safe !
    ObjectsDetectionProcessor(objectsDetectionPredictor)
  }
}
