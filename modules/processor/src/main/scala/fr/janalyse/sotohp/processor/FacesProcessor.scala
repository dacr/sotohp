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

import java.nio.file.Path
import scala.jdk.CollectionConverters.*
import wvlet.airframe.ulid.ULID

case class FacesDetectionIssue(message: String, exception: Throwable)

class FacesProcessor(facesPredictor: Predictor[Image, DetectedObjects]) extends Processor {

  private def makeFaceId(photo: Photo): FaceId = {
    // Note : up 2^80 possible values for the same millis
    FaceId(ULID.ofMillis(photo.timestamp.toInstant.toEpochMilli))
  }

  private def doDetectFaces(photo: Photo, path: Path): List[DetectedFace] = {
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
            someoneId = None,
            box = BoundingBox(
              x = ob.getBoundingBox.getBounds.getX,
              y = ob.getBoundingBox.getBounds.getY,
              width = ob.getBoundingBox.getBounds.getWidth,
              height = ob.getBoundingBox.getBounds.getHeight
            ),
            faceId = makeFaceId(photo)
          )
        )

    detected
  }

  private def detectFaces(photo: Photo) = {
    for {
      input      <- getBestInputPhotoFile(photo)
      knownFaces <- PhotoStoreService.photoFacesGet(photo.source.photoId)
      // knownFaces = None
      faces      <- ZIO
                      .from(knownFaces)
                      .orElse(
                        ZIO
                          .attempt(doDetectFaces(photo, input))
                          .mapError(th => FacesDetectionIssue("Couldn't analyze photo", th))
                          .tap(faces => ZIO.log(s"found ${faces.size} faces"))
                          .map(faces => PhotoFaces(faces = faces, count = faces.size))
                      )
      _          <- PhotoStoreService
                      .photoFacesUpsert(photo.source.photoId, faces)
                      .when(knownFaces.isEmpty)
    } yield photo.copy(foundFaces = Some(faces))
  }

  /** analyse photo content using various neural networks
    *
    * @param photo
    * @return
    *   photo with updated miniatures field if some changes have occurred
    */
  def analyze(photo: Photo): RIO[PhotoStoreService, Photo] = {
    // TODO : quick, dirty & unfinished first implementation
    detectFaces(photo)
      .logError("Faces detection issue")
      .option
      .someOrElse(photo)
      @@ annotated("photoId" -> photo.source.photoId.toString())
      @@ annotated("photoPath" -> photo.source.original.path.toString)
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
