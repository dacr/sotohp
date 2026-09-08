package fr.janalyse.sotohp.processor

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import fr.janalyse.sotohp.core.CoreIssue
import fr.janalyse.sotohp.media.imaging.BasicImaging
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.processor.config.CaptionerConfig
import fr.janalyse.sotohp.processor.model.*
import zio.*
import zio.ZIOAspect.*

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.Files
import java.time.Duration
import java.util.Base64
import javax.imageio.ImageIO

trait CaptionIssue(message: String, mayBeErr: Option[Throwable]) extends Exception with CoreIssue
case class CaptionGeneralIssue(message: String)                extends CaptionIssue(message, None)
case class CaptionRequestIssue(message: String, err: Throwable) extends CaptionIssue(message, Some(err))

// Ollama /api/generate wire types (only the fields we use).
private case class OllamaGenerateRequest(model: String, prompt: String, images: List[String], stream: Boolean, options: Map[String, Int])
private case class OllamaGenerateResponse(response: String = "", done: Boolean = false)
private object OllamaGenerateRequest { given JsonValueCodec[OllamaGenerateRequest] = JsonCodecMaker.make }
private object OllamaGenerateResponse { given JsonValueCodec[OllamaGenerateResponse] = JsonCodecMaker.make(CodecMakerConfig.withAllowRecursiveTypes(true)) }

/** Image-to-text: asks a local Ollama vision model to describe a photo.
  *
  * A plain HTTP client, not a DJL predictor. When `config.enabled` is false every call is a
  * fast no-op returning an unsuccessful [[OriginalCaption]] (so nothing is stored and the step
  * retries once the captioner is turned on).
  */
class CaptionProcessor(config: CaptionerConfig) extends Processor {

  val enabled: Boolean = config.enabled

  private lazy val httpClient: HttpClient =
    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(config.timeoutSeconds.toLong.max(1))).build()

  override def close(): Unit = ()

  private def jpegBytes(original: Original): IO[CoreIssue, Array[Byte]] = {
    if (config.maxImageSize <= 0)
      getOriginalBestInputFileForProcessors(original)
        .flatMap(path => ZIO.attemptBlocking(Files.readAllBytes(path)))
        .mapError(err => CaptionRequestIssue("Couldn't read normalized image", err))
    else
      loadOriginalBestInputFileForProcessors(original)
        .flatMap(image =>
          ZIO.attemptBlocking {
            val resized: BufferedImage = BasicImaging.resize(image, config.maxImageSize, config.maxImageSize)
            val out                    = new ByteArrayOutputStream()
            ImageIO.write(resized, "jpg", out)
            out.toByteArray
          }
        )
        .mapError(err => CaptionRequestIssue("Couldn't downscale image for captioning", err))
  }

  private def requestCaption(imageBase64: String): IO[CoreIssue, String] = {
    val payload = OllamaGenerateRequest(
      model = config.model,
      prompt = config.prompt,
      images = List(imageBase64),
      stream = false,
      options = Map("temperature" -> 0)
    )
    val request = HttpRequest
      .newBuilder()
      .uri(URI.create(s"${config.baseUrl.stripSuffix("/")}/api/generate"))
      .timeout(Duration.ofSeconds(config.timeoutSeconds.toLong.max(1)))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofByteArray(writeToArray(payload)))
      .build()

    ZIO
      .attemptBlocking(httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray()))
      .mapError(err => CaptionRequestIssue(s"Ollama request to ${config.baseUrl} failed", err))
      .flatMap { response =>
        if (response.statusCode() / 100 != 2)
          ZIO.fail(CaptionGeneralIssue(s"Ollama returned HTTP ${response.statusCode()}: ${new String(response.body()).take(300)}"))
        else
          ZIO
            .attempt(readFromArray[OllamaGenerateResponse](response.body()).response)
            .mapError(err => CaptionRequestIssue("Couldn't parse Ollama response", err))
      }
  }

  /** Normalise: collapse whitespace, drop a leading "This image shows " / "The photo shows "
    * style preamble some models add, trim. */
  private def tidy(raw: String): Option[String] = {
    val collapsed = raw.trim.replaceAll("\\s+", " ")
    val stripped  = collapsed.replaceFirst("(?i)^(this (image|picture|photo)|the (image|picture|photo)) (shows|depicts|is of|features) ", "")
    Option(stripped.trim).filter(_.nonEmpty)
  }

  def caption(original: Original): IO[CoreIssue, OriginalCaption] = {
    val logic =
      if (!enabled)
        Clock.currentDateTime.map(now => OriginalCaption(original, ProcessedStatus(successful = false, timestamp = now), None))
      else
        for {
          now      <- Clock.currentDateTime
          mayBeText <- jpegBytes(original)
                         .map(bytes => Base64.getEncoder.encodeToString(bytes))
                         .flatMap(requestCaption)
                         .map(tidy)
                         .mapError(err => CaptionGeneralIssue(s"Unable to caption image: $err"))
                         .logError("Caption issue")
                         .option
                         .map(_.flatten)
          status    = ProcessedStatus(successful = mayBeText.isDefined, timestamp = now)
        } yield OriginalCaption(original, status, mayBeText)

    logic
      @@ annotated("originalId" -> original.id.asString)
      @@ annotated("originalPath" -> original.absoluteMediaPath.toString)
  }
}

object CaptionProcessor {
  def allocate(): IO[CaptionIssue, CaptionProcessor] =
    CaptionerConfig.config
      .mapError(err => CaptionGeneralIssue(s"Unable to load captioner configuration: $err"))
      .map(CaptionProcessor(_))
}
