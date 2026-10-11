package fr.janalyse.sotohp.processor

import ai.djl.inference.Predictor
import ai.djl.modality.cv.{Image, ImageFactory}
import ai.djl.repository.zoo.{Criteria, ZooModel}
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import fr.janalyse.sotohp.core.CoreIssue
import fr.janalyse.sotohp.media.imaging.BasicImaging
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.processor.config.FaceFeaturesConfig
import fr.janalyse.sotohp.processor.model.*
import zio.*
import zio.ZIOAspect.*

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import java.util.Base64
import javax.imageio.{IIOImage, ImageIO, ImageWriteParam}

abstract class FaceFeaturesIssue(message: String, mayByErr: Option[Throwable]) extends Exception(message, mayByErr.orNull) with CoreIssue
case class FaceFeaturesGeneralIssue(message: String)                  extends FaceFeaturesIssue(message, None)
case class FaceFeaturesExtractIssue(message: String, err: Throwable)  extends FaceFeaturesIssue(message, Some(err))
// The embedding server could not be used at all (unreachable, timeout, server-side error, unknown
// model). It says nothing about the photo, so callers must not record it as a features attempt.
case class FaceFeaturesServiceUnavailableIssue(message: String, err: Option[Throwable] = None) extends FaceFeaturesIssue(message, err)

/** Computes one embedding per detected face.
  *
  * The engine is chosen by configuration (see [[FaceFeaturesConfig]]) ; both share the same
  * shape : pick the faces worth embedding, cut each one out of the image, embed the cut. On a
  * photo-level failure the whole original is reported unsuccessful with no features, so a batch
  * sync is never stopped by one bad photo and the step is retried later. An unusable embedding
  * server ([[FaceFeaturesServiceUnavailableIssue]]) is not the photo's fault : it is propagated so
  * that nothing gets recorded.
  */
trait FaceFeaturesProcessor extends Processor {

  /** Tag stored with every vector this processor computes. Vectors carrying different tags live in
    * different spaces and must never be compared. `None` is the historical DJL model.
    */
  def modelTag: Option[String]

  /** The image the faces boxes are cut from when the caller doesn't provide one. */
  protected def loadImage(original: Original): IO[CoreIssue, BufferedImage]

  /** Whether `face` is worth embedding at all, given the image it would be cut from. */
  protected def selectable(face: Face, image: BufferedImage): Boolean

  /** The region of `image` this engine embeds for `face`. */
  def faceCrop(face: Face, image: BufferedImage): IO[CoreIssue, BufferedImage]

  /** Embeds a face image as cut by [[faceCrop]] (possibly transformed afterwards, see the drift
    * CLI). `None` when the engine finds no face in it.
    */
  def embedCrop(crop: BufferedImage): IO[CoreIssue, Option[Array[Float]]]

  /** Extracts features from faces detected in the input `OriginalFaces` object while applying filtering and processing.
    *
    * @param faces
    *   the `OriginalFaces` object containing the original image and the list of detected faces to process
    * @return
    *   an `IO` effect resulting in either a `CoreIssue` (in case of an error) or an `OriginalFaceFeatures` containing the processed face features
    */
  def extractFaceFeatures(faces: OriginalFaces): IO[CoreIssue, OriginalFaceFeatures] = {
    if (faces.faces.isEmpty)
      // nothing to cut, don't pay for decoding the image
      Clock.currentDateTime.map(now => OriginalFaceFeatures(faces.original, ProcessedStatus(successful = true, timestamp = now), Nil, modelTag))
    else
      for {
        originalImage <- loadImage(faces.original)
        result        <- extractFaceFeatures(faces, originalImage)
      } yield result
  }

  /** Extracts features from faces using the provided image instead of loading it.
    * Useful when the rotation to apply differs from `original.orientation` (e.g. the user customized media orientation).
    */
  def extractFaceFeatures(faces: OriginalFaces, originalImage: BufferedImage): IO[CoreIssue, OriginalFaceFeatures] = {
    val logic = for {
      now               <- Clock.currentDateTime
      selectedFaces      = faces.faces.filter(face => selectable(face, originalImage))
      mayBeFaceFeatures <- ZIO
                             .foreach(selectedFaces) { face =>
                               faceCrop(face, originalImage)
                                 .flatMap(embedCrop)
                                 .map(_.map(vector => FaceFeatures(faceId = face.faceId, features = vector, model = modelTag)))
                                 @@ annotated("faceId" -> face.faceId.toString)
                             }
                             .map(_.flatten)
                             .map(Some(_))
                             .catchAll {
                               case err: FaceFeaturesServiceUnavailableIssue => ZIO.fail(err)
                               case err                                      => ZIO.logWarning(s"Unable to compute face features: $err").as(None)
                             }
      status             = ProcessedStatus(successful = mayBeFaceFeatures.isDefined, timestamp = now)
      features           = mayBeFaceFeatures.getOrElse(Nil)
    } yield OriginalFaceFeatures(faces.original, status, features, modelTag)

    logic
      .logError("Face features issue")
      @@ annotated("originalId" -> faces.original.id.asString)
      @@ annotated("originalPath" -> faces.original.absoluteMediaPath.toString)
  }
}

object FaceFeaturesProcessor {

  /** Allocates the processor of the configured engine. */
  def allocate(): IO[FaceFeaturesIssue, FaceFeaturesProcessor] =
    FaceFeaturesConfig.config
      .mapError(err => FaceFeaturesGeneralIssue(s"Unable to load face features configuration: $err"))
      .flatMap(config => allocate(config.engine))

  /** Allocates the processor of the given engine, the rest of the configuration being the ambient one. */
  def allocate(engine: String): IO[FaceFeaturesIssue, FaceFeaturesProcessor] =
    FaceFeaturesConfig.config
      .mapError(err => FaceFeaturesGeneralIssue(s"Unable to load face features configuration: $err"))
      .flatMap { config =>
        engine match {
          case FaceFeaturesConfig.djlEngine     => DjlFaceFeaturesProcessor.allocate()
          case FaceFeaturesConfig.localaiEngine => ZIO.succeed(LocalAIFaceFeaturesProcessor(config))
          case other                            => ZIO.fail(FaceFeaturesGeneralIssue(s"Unknown face features engine '$other', expected '${FaceFeaturesConfig.djlEngine}' or '${FaceFeaturesConfig.localaiEngine}'"))
        }
      }

  /** The model tag the configured engine stores with its vectors, without allocating it. */
  def configuredModelTag: IO[FaceFeaturesIssue, Option[String]] =
    FaceFeaturesConfig.config
      .mapError(err => FaceFeaturesGeneralIssue(s"Unable to load face features configuration: $err"))
      .map(config => if (config.engine == FaceFeaturesConfig.localaiEngine) Some(LocalAIFaceFeaturesProcessor.modelTagOf(config)) else None)
}

// =====================================================================================================================

/** The historical engine : an in-process DJL/PyTorch model fed with the unaligned detector box cut
  * out of the 1920px normalized rendition. Kept as it always behaved so its vectors stay comparable
  * with the ones already stored.
  */
class DjlFaceFeaturesProcessor(predictor: Predictor[Image, Array[Float]]) extends FaceFeaturesProcessor {

  override val modelTag: Option[String] = None

  override def close(): Unit = {
    predictor.close()
  }

  private val minRatio = 70d / 1900d // TODO use config parameter

  override protected def loadImage(original: Original): IO[CoreIssue, BufferedImage] =
    loadOriginalBestInputFileForProcessors(original)

  override protected def selectable(face: Face, image: BufferedImage): Boolean =
    face.box.width.value >= minRatio || face.box.height.value >= minRatio

  override def faceCrop(face: Face, originalImage: BufferedImage): IO[CoreIssue, BufferedImage] = {
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
      .mapError(err => FaceFeaturesExtractIssue(s"Couldn't extract face from image [$fixedX, $fixedY, $fixedWidth, $fixedHeight]", err))
  }

  override def embedCrop(crop: BufferedImage): IO[CoreIssue, Option[Array[Float]]] = {
    for {
      faceImageResized <- ZIO
                            .attempt(BasicImaging.resize(crop, 160, 160))
                            .mapError(err => FaceFeaturesExtractIssue("Couldn't resize face image", err))
      djlImage         <- ZIO
                            .attempt(ImageFactory.getInstance().fromImage(faceImageResized))
                            .mapError(err => FaceFeaturesExtractIssue("Couldn't load face Image", err))
      features         <- ZIO
                            .attempt(predictor.predict(djlImage))
                            .mapError(err => FaceFeaturesExtractIssue("Couldn't predict face features", err))
    } yield Some(features)
  }
}

object DjlFaceFeaturesProcessor {
  def allocate(): IO[FaceFeaturesExtractIssue, DjlFaceFeaturesProcessor] = {
    for {
      semaphore <- Semaphore.make(1)
      logic      = ZIO
                     .attemptBlocking {
                       val criteria: Criteria[Image, Array[Float]] =
                         Criteria
                           .builder()
                           .setTypes(classOf[Image], classOf[Array[Float]])
                           .optModelUrls("https://resources.djl.ai/test-models/pytorch/face_feature.zip")
                           .optModelName("face_feature") // specify model file prefix
                           .optTranslator(new FeatureExtraction.FaceFeatureTranslator())
                           // .optProgress(new ProgressBar())
                           .optEngine("PyTorch")
                           // Use PyTorch engine
                           .build()

                       val model: ZooModel[Image, Array[Float]] = criteria.loadModel()
                       val predictor                            = model.newPredictor() // not thread safe !
                       DjlFaceFeaturesProcessor(predictor)
                     }
                     .logError("Face features processor allocation issue")
                     .mapError(err => FaceFeaturesExtractIssue("Unable to allocate face features processor", err))
      result    <- semaphore.withPermit(logic)
    } yield result
  }
}

// =====================================================================================================================

// LocalAI /v1/face/embed wire types (only the fields we use).
private case class LocalAIFaceEmbedRequest(model: String, img: String)
private case class LocalAIFaceEmbedResponse(embedding: Array[Float] = Array.empty, dim: Int = 0, model: String = "")
private object LocalAIFaceEmbedRequest  { given JsonValueCodec[LocalAIFaceEmbedRequest] = JsonCodecMaker.make }
private object LocalAIFaceEmbedResponse { given JsonValueCodec[LocalAIFaceEmbedResponse] = JsonCodecMaker.make }

/** A dedicated face recognition model (InsightFace ArcFace behind an SCRFD detector) served by a
  * LocalAI server.
  *
  * LocalAI's `/v1/face/embed` answers with the embedding of the most confident face it detects in
  * the image it is given, and nothing else - no box - so a whole photo can't be sent : the vectors
  * couldn't be attached to our own faces. Instead each face already known (its `faceId`, and the
  * human identifications hanging on it, stay untouched) is sent as its own crop : the detector box
  * plus `cropMargin` of context on each side, cut from the full-resolution original rather than
  * the 1920px rendition. LocalAI re-detects the face in there, aligns it on its 5 landmarks (eyes,
  * nose, mouth corners) and embeds the aligned 112x112 chip - the alignment is what makes the
  * vector insensitive to in-plane rotation and head tilt.
  *
  * Stateless and thread-safe (a plain HTTP client), unlike the DJL predictor.
  */
class LocalAIFaceFeaturesProcessor(config: FaceFeaturesConfig) extends FaceFeaturesProcessor {

  override val modelTag: Option[String] = Some(LocalAIFaceFeaturesProcessor.modelTagOf(config))

  // A face larger than this (longest side, in px) is downscaled before being sent : the server's
  // detector works at 640px anyway and the aligned chip is 112px, the extra pixels are just bytes.
  private val maxSentSize = 640

  private lazy val httpClient: HttpClient =
    HttpClient
      .newBuilder()
      .version(HttpClient.Version.HTTP_1_1)
      .connectTimeout(Duration.ofSeconds(config.timeoutSeconds.toLong.max(1)))
      .build()

  override def close(): Unit = ()

  /** Full-resolution original turned by its EXIF orientation - the frame the normalized rendition,
    * hence the stored boxes, are expressed in. Falls back to the rendition if the original can't
    * be decoded.
    */
  override protected def loadImage(original: Original): IO[CoreIssue, BufferedImage] =
    ZIO
      .attemptBlocking {
        val image = BasicImaging.load(original.absoluteMediaPath)
        BasicImaging.rotate(image, original.orientation.map(_.rotationDegrees).getOrElse(0).toDouble)
      }
      .tapError(err => ZIO.logWarning(s"Couldn't load full resolution original, using the normalized rendition : ${err.getMessage}"))
      .orElse(loadOriginalBestInputFileForProcessors(original))

  override protected def selectable(face: Face, image: BufferedImage): Boolean = {
    val longestSide = math.max(face.box.width.value * image.getWidth, face.box.height.value * image.getHeight)
    longestSide >= config.minFaceSize
  }

  override def faceCrop(face: Face, image: BufferedImage): IO[CoreIssue, BufferedImage] = {
    val (x, y, width, height) = LocalAIFaceFeaturesProcessor.marginRectangle(face.box, image.getWidth, image.getHeight, config.cropMargin)
    ZIO
      .attempt(image.getSubimage(x, y, width, height))
      .mapError(err => FaceFeaturesExtractIssue(s"Couldn't extract face from image [$x, $y, $width, $height]", err))
  }

  override def embedCrop(crop: BufferedImage): IO[CoreIssue, Option[Array[Float]]] = {
    for {
      jpeg     <- ZIO
                    .attemptBlocking {
                      val longest = math.max(crop.getWidth, crop.getHeight)
                      val sent    = if (longest > maxSentSize) BasicImaging.resize(crop, maxSentSize, maxSentSize) else crop
                      LocalAIFaceFeaturesProcessor.jpegBytes(sent)
                    }
                    .mapError(err => FaceFeaturesExtractIssue("Couldn't encode face image", err))
      dataUri   = s"data:image/jpeg;base64,${Base64.getEncoder.encodeToString(jpeg)}"
      embedding <- requestEmbedding(dataUri)
    } yield embedding
  }

  private def requestEmbedding(dataUri: String): IO[CoreIssue, Option[Array[Float]]] = {
    val request = HttpRequest
      .newBuilder()
      .uri(URI.create(s"${config.baseUrl.stripSuffix("/")}/v1/face/embed"))
      .timeout(Duration.ofSeconds(config.timeoutSeconds.toLong.max(1)))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofByteArray(writeToArray(LocalAIFaceEmbedRequest(model = config.model, img = dataUri))))
      .build()

    ZIO
      .attemptBlocking(httpClient.send(request, HttpResponse.BodyHandlers.ofString()))
      .mapError(err => FaceFeaturesServiceUnavailableIssue(s"LocalAI request to ${config.baseUrl} failed: ${err.getMessage}", Some(err)))
      .flatMap { response =>
        val body = Option(response.body()).getOrElse("")
        if (response.statusCode() / 100 == 2)
          ZIO
            .attempt(readFromString[LocalAIFaceEmbedResponse](body))
            .mapError(err => FaceFeaturesExtractIssue(s"Couldn't decode LocalAI face embedding response: ${body.take(300)}", err))
            .map(decoded => Option(decoded.embedding).filter(_.nonEmpty))
        else if (LocalAIFaceFeaturesProcessor.isNoFaceAnswer(response.statusCode(), body))
          // The server's detector found no face in our crop (profile, occlusion, a false positive of
          // our own detector) - nothing to embed, but nothing wrong either.
          ZIO.logDebug(s"LocalAI found no face in the crop : ${body.take(200)}").as(None)
        else if (LocalAIFaceFeaturesProcessor.isServiceFailure(response.statusCode()))
          ZIO.fail(FaceFeaturesServiceUnavailableIssue(s"LocalAI returned HTTP ${response.statusCode()}: ${body.take(300)}"))
        else
          ZIO.fail(FaceFeaturesGeneralIssue(s"LocalAI returned HTTP ${response.statusCode()}: ${body.take(300)}"))
      }
  }
}

object LocalAIFaceFeaturesProcessor {

  def modelTagOf(config: FaceFeaturesConfig): String = s"localai:${config.model}"

  private val noFacePattern = "(?i)no face".r

  /** Whether a failed `/v1/face/embed` answer means "no face in this image". The backend says so with
    * a gRPC NOT_FOUND ("no face detected"), which LocalAI turns into an HTTP 404 - but its error handler
    * then replaces the message with a generic "Resource not found" (seen with LocalAI 4.10). An unknown
    * model is a 404 too, told apart by its explicit message ("model ... not found").
    */
  private[processor] def isNoFaceAnswer(status: Int, body: String): Boolean =
    noFacePattern.findFirstIn(body).isDefined ||
      (status == 404 && body.contains("Resource not found"))

  /** Whether a failed `/v1/face/embed` answer (no-face answers set aside) means the server can't be
    * used for any photo right now - server-side error, unknown model (404), missing credentials,
    * throttling - rather than something wrong with the crop we sent (400, 413, ...).
    */
  private[processor] def isServiceFailure(status: Int): Boolean =
    status >= 500 || Set(401, 403, 404, 408, 429).contains(status)

  /** The detector box, grown by `margin` (a fraction of the box size) on each side, in pixels of a
    * `width` x `height` image and clamped to stay inside it.
    *
    * @return
    *   (x, y, width, height) in pixels
    */
  def marginRectangle(box: BoundingBox, width: Int, height: Int, margin: Double): (Int, Int, Int, Int) = {
    val boxX      = box.x.value * width
    val boxY      = box.y.value * height
    val boxWidth  = box.width.value * width
    val boxHeight = box.height.value * height
    val left      = math.max(0, math.round(boxX - margin * boxWidth).toInt)
    val top       = math.max(0, math.round(boxY - margin * boxHeight).toInt)
    val right     = math.min(width, math.round(boxX + boxWidth + margin * boxWidth).toInt)
    val bottom    = math.min(height, math.round(boxY + boxHeight + margin * boxHeight).toInt)
    (left, top, math.max(1, right - left), math.max(1, bottom - top))
  }

  /** JPEG encoding at high quality ; drawn onto an opaque RGB canvas first since the JPEG writer
    * rejects images carrying an alpha channel.
    */
  def jpegBytes(image: BufferedImage, quality: Float = 0.92f): Array[Byte] = {
    val rgb =
      if (image.getType == BufferedImage.TYPE_INT_RGB) image
      else {
        val copy     = new BufferedImage(image.getWidth, image.getHeight, BufferedImage.TYPE_INT_RGB)
        val graphics = copy.createGraphics()
        try graphics.drawImage(image, 0, 0, null)
        finally graphics.dispose()
        copy
      }
    val writer = ImageIO.getImageWritersByFormatName("jpg").next()
    val params = writer.getDefaultWriteParam
    params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT)
    params.setCompressionQuality(quality)
    val out    = new ByteArrayOutputStream()
    val stream = ImageIO.createImageOutputStream(out)
    try {
      writer.setOutput(stream)
      writer.write(null, IIOImage(rgb, null, null), params)
    } finally {
      stream.close()
      writer.dispose()
    }
    out.toByteArray
  }
}
