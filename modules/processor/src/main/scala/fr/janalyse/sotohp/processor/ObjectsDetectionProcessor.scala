package fr.janalyse.sotohp.processor

import ai.djl.Application
import ai.djl.inference.Predictor
import ai.djl.modality.Classifications
import ai.djl.modality.cv.{Image, ImageFactory}
import ai.djl.modality.cv.output.DetectedObjects
import ai.djl.repository.zoo.{Criteria, ModelZoo}
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.store.*
import zio.*
import zio.ZIOAspect.*

import java.nio.file.Path
import scala.jdk.CollectionConverters.*

case class ObjectsDetectionIssue(message: String, exception: Throwable) extends Exception(message, exception)

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
      input        <- getBestInputPhotoFile(photo)
      knownObjects <- PhotoStoreService.photoObjectsGet(photo.source.photoId)
      // knownObjects = None
      objects      <- ZIO
                        .from(knownObjects)
                        .orElse(
                          ZIO
                            .attempt(doDetectObjects(input))
                            .tap(objs => ZIO.log(s"found objects : ${objs.mkString(",")}"))
                            .map(detectedObjects => PhotoObjects(objects = detectedObjects))
                        )
      _            <- PhotoStoreService
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
  def analyze(photo: Photo): RIO[PhotoStoreService, Photo] = {
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
      .optFilters(Map("backbone" -> "mobilenet1.0").asJava)
      // .optProgress(new ProgressBar)
      .build

  lazy val objectDetectionsModel = ModelZoo.loadModel(objectDetectionsCriteria)

  def allocate(): ObjectsDetectionProcessor = {
    val objectsDetectionPredictor = objectDetectionsModel.newPredictor() // not thread safe !
    ObjectsDetectionProcessor(objectsDetectionPredictor)
  }
}
