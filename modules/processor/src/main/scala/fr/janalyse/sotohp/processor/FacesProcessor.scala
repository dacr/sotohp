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
import fr.janalyse.sotohp.media.imaging.BasicImaging

import java.nio.file.Path
import scala.jdk.CollectionConverters.*
import wvlet.airframe.ulid.ULID

import java.awt.image.BufferedImage

case class FacesDetectionIssue(message: String, exception: Throwable)

class FacesProcessor(facesPredictor: Predictor[Image, DetectedObjects]) extends Processor {

  override def close(): Unit                         = {
    facesPredictor.close()
  }
  private def makeFaceId(original: Original): FaceId = {
    // Note: up 2^80 possible values for the same millis
    FaceId(ULID.ofMillis(original.timestamp.toInstant.toEpochMilli))
  }

  def extractThenCacheFaceImageFromOriginal(
    face: DetectedFace,
    originalImage: BufferedImage
  ): IO[FacesDetectionIssue, Unit] = {
    val x           = (face.box.x.value * originalImage.getWidth).toInt
    val y           = (face.box.y.value * originalImage.getHeight).toInt
    val width       = (face.box.width.value * originalImage.getWidth).toInt
    val height      = (face.box.height.value * originalImage.getHeight).toInt
    val fixedX      = if (x < 0) 0 else x
    val fixedY      = if (y < 0) 0 else y
    val fixedWidth  = if (fixedX + width < originalImage.getWidth()) width else originalImage.getWidth - fixedX
    val fixedHeight = if (fixedY + height < originalImage.getHeight()) height else originalImage.getHeight - fixedY

    ZIO
      .attempt(originalImage.getSubimage(fixedX, fixedY, fixedWidth, fixedHeight))
      .mapError(err => FacesDetectionIssue(s"Couldn't extract face from image [$fixedX, $fixedY, $fixedWidth, $fixedHeight]", err))
      .flatMap(buff =>
        ZIO
          .attempt(BasicImaging.save(face.path.path, buff))
          .mapError(err => FacesDetectionIssue(s"Couldn't save selected face from image [$fixedX, $fixedY, $fixedWidth, $fixedHeight]", err))
          .logError("Faces caching issue")
      )
  }

  def getOriginalBufferedImage(original: Original): IO[FacesDetectionIssue, BufferedImage] = {
    for {
      originalBufferedImage <- ZIO
                                 .attemptBlocking(BasicImaging.load(original.absoluteMediaPath))
                                 .mapError(th => FacesDetectionIssue("Unable to load original image", th))
      originalBufferedImage <- ZIO
                                 .attemptBlocking(
                                   BasicImaging.rotate(
                                     originalBufferedImage,
                                     original.orientation.map(_.rotationDegrees).getOrElse(0)
                                   )
                                 )
                                 .mapError(th => FacesDetectionIssue("Unable to rotate the image", th))
    } yield originalBufferedImage
  }

  def buildDetectedFace(original: Original, box: BoundingBox): IO[FacesDetectionIssue, DetectedFace] = {
    val faceId = makeFaceId(original)
    for {
      facePath <- getOriginalFaceFilePath(original, faceId)
                    .mapError(th => FacesDetectionIssue("Unable to get face cache path", th))
      _        <- ZIO
                    .attempt(facePath.toFile.getParentFile.mkdirs()) // TODO not optimal !!
                    .mapError(th => FacesDetectionIssue("Unable to create face path", th))
    } yield DetectedFace(
      box = box,
      path = DetectedFacePath(facePath),
      faceId = faceId,
      originalId = original.id,
      timestamp = original.timestamp,
      identifiedPersonId = None,
      inferredIdentifiedPersonId = None
    )
  }

  private def doDetectFaces(original: Original, path: Path): IO[FacesDetectionIssue, List[DetectedFace]] = {
    for {
      detectedObjects       <- ZIO
                                 .attemptBlocking {
                                   val loadedImage: Image         = ImageFactory.getInstance().fromFile(path)
                                   val detection: DetectedObjects = facesPredictor.predict(loadedImage)
                                   detection
                                     .items()
                                     .iterator()
                                     .asScala
                                     .toList
                                     .asInstanceOf[List[DetectedObjects.DetectedObject]]
                                     .filter(_.getProbability >= 0.7d) // TODO hardcoded config
                                     .filter { ob =>
                                       val bounds           = ob.getBoundingBox.getBounds
                                       val widthInOriginal  = original.dimension.map(_.width.value).getOrElse(0) * bounds.getWidth
                                       val heightInOriginal = original.dimension.map(_.height.value).getOrElse(0) * bounds.getHeight
                                       (heightInOriginal >= 32 && widthInOriginal >= 32) // TODO hardcoded config
                                     }
                                 }
                                 .mapError(th => FacesDetectionIssue("Unable to detect people faces", th))
      detectedFaces         <- ZIO.foreach(detectedObjects)(ob => {
                                 val box = BoundingBox(
                                   x = XAxis(ob.getBoundingBox.getBounds.getX),
                                   y = YAxis(ob.getBoundingBox.getBounds.getY),
                                   width = BoxWidth(ob.getBoundingBox.getBounds.getWidth),
                                   height = BoxHeight(ob.getBoundingBox.getBounds.getHeight)
                                 )
                                 buildDetectedFace(original, box)
                               })
      originalBufferedImage <- getOriginalBufferedImage(original)
      _                     <- ZIO.foreachDiscard(detectedFaces)(face => extractThenCacheFaceImageFromOriginal(face, originalBufferedImage))
    } yield detectedFaces.filter(_.path.path.toFile.exists()) // filtering because already encounter saving issue - such as invalid colorspace errors
  }

  /** Extracts faces detected in the image represented by the provided original instance.
    *
    * @param original
    *   The original image metadata and associated details, used to locate and analyze the image file for face detection.
    */
  def extractFaces(original: Original) = {
    val logic = for {
      now        <- Clock.currentDateTime
      input      <- getOriginalBestInputFileForProcessors(original)
      mayBeFaces <- doDetectFaces(original, input)
                      .tap(faces => ZIO.log(s"found ${faces.size} faces"))
                      .logError("Faces detection issue")
                      .option
      status      = ProcessedStatus(successful = mayBeFaces.isDefined, timestamp = now)
      faces       = mayBeFaces.getOrElse(Nil)
    } yield OriginalFaces(original, status, faces)
    logic
      @@ annotated("originalId" -> original.id.asString)
      @@ annotated("originalPath" -> original.absoluteMediaPath.toString)
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

  def allocate(): IO[FacesDetectionIssue, FacesProcessor] = {
    for {
      semaphore <- Semaphore.make(1)
      logic      = ZIO
                     .attemptBlocking {
                       val facesCriteria: Criteria[Image, DetectedObjects] =
                         Criteria
                           .builder()
                           .setTypes(classOf[Image], classOf[DetectedObjects])
                           .optModelUrls("https://resources.djl.ai/test-models/pytorch/retinaface.zip")
                           .optModelName("retinaface") // specify model file prefix
                           .optTranslator(translator)
                           // .optProgress(new ProgressBar())
                           .optEngine("PyTorch")
                           // Use PyTorch engine
                           .build()

                       val facesModel: ZooModel[Image, DetectedObjects] = facesCriteria.loadModel()
                       val facesPredictor                               = facesModel.newPredictor() // not thread safe !
                       FacesProcessor(facesPredictor)
                     }
                     .logError("Faces processor allocation issue")
                     .mapError(err => FacesDetectionIssue("Unable to allocate faces processor", err))
      result    <- semaphore.withPermit(logic)
    } yield result
  }
}
