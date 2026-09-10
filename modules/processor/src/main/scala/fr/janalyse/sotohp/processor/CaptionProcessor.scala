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
import scala.jdk.CollectionConverters.*

abstract class CaptionIssue(message: String, mayBeErr: Option[Throwable]) extends Exception(message, mayBeErr.orNull) with CoreIssue
case class CaptionGeneralIssue(message: String)                extends CaptionIssue(message, None)
case class CaptionRequestIssue(message: String, err: Throwable) extends CaptionIssue(s"$message: ${err.getMessage}", Some(err))

// Ollama /api/generate wire types (only the fields we use).
private case class OllamaOptions(temperature: Double, num_predict: Int, repeat_penalty: Double)
private case class OllamaGenerateRequest(model: String, prompt: String, images: List[String], stream: Boolean, options: OllamaOptions)
// One line of the streamed response, or a `{"error": "..."}` line.
private case class OllamaStreamChunk(response: String = "", done: Boolean = false, error: String = "")
private object OllamaGenerateRequest { given JsonValueCodec[OllamaGenerateRequest] = JsonCodecMaker.make }
private object OllamaStreamChunk     { given JsonValueCodec[OllamaStreamChunk]     = JsonCodecMaker.make }

/** Image-to-text: asks a local Ollama vision model to describe a photo.
  *
  * A plain HTTP client, not a DJL predictor. When `config.enabled` is false every call is a
  * fast no-op returning an unsuccessful [[OriginalCaption]] (so nothing is stored and the step
  * retries once the captioner is turned on).
  *
  * The request is streamed (`stream: true`) and the chunks reassembled here: Ollama's
  * non-streaming responses drop the first output token for some models, which streaming avoids.
  */
class CaptionProcessor(config: CaptionerConfig) extends Processor {

  val enabled: Boolean = config.enabled

  // Hard cap on a stored caption. A well-behaved model stays well under this; anything longer is
  // truncated to the first sentence by `tidy`.
  private val maxCaptionChars = 400

  private lazy val httpClient: HttpClient =
    HttpClient
      .newBuilder()
      .version(HttpClient.Version.HTTP_1_1) // Ollama is HTTP/1.1; skip the h2c upgrade dance
      .connectTimeout(Duration.ofSeconds(config.timeoutSeconds.toLong.max(1)))
      .build()

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
      stream = true,
      // num_predict caps runaway generations; repeat_penalty discourages the degenerate
      // token loops small vision models (e.g. moondream) sometimes fall into.
      options = OllamaOptions(temperature = 0d, num_predict = 120, repeat_penalty = 1.3d)
    )
    val request = HttpRequest
      .newBuilder()
      .uri(URI.create(s"${config.baseUrl.stripSuffix("/")}/api/generate"))
      .timeout(Duration.ofSeconds(config.timeoutSeconds.toLong.max(1)))
      .header("Content-Type", "application/json")
      .header("Accept", "application/x-ndjson")
      .POST(HttpRequest.BodyPublishers.ofByteArray(writeToArray(payload)))
      .build()

    ZIO
      .attemptBlocking {
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines())
        if (response.statusCode() / 100 != 2) {
          val body = response.body().iterator().asScala.take(5).mkString(" ")
          throw CaptionGeneralIssue(s"Ollama returned HTTP ${response.statusCode()}: ${body.take(300)}")
        }
        val sb = new StringBuilder()
        response.body().iterator().asScala.foreach { line =>
          val trimmed = line.trim
          if (trimmed.nonEmpty) {
            val chunk = readFromString[OllamaStreamChunk](trimmed)
            if (chunk.error.nonEmpty) throw CaptionGeneralIssue(s"Ollama error: ${chunk.error}")
            sb.append(chunk.response)
          }
        }
        sb.toString
      }
      .mapError {
        case issue: CaptionIssue => issue
        case err                 => CaptionRequestIssue(s"Ollama request to ${config.baseUrl} failed", err)
      }
  }

  // Strips a leading "The image shows " / "Cette photo montre " style preamble, EN or FR.
  private val preamble =
    ("(?i)^(" +
      "(the |this |it )?(image |picture |photo |photograph |scene )?(shows|depicts|features|contains|portrays|is(?: of| a| an)?)" +
      "|(cette |la |l'|une |ce |le )?(image |photo |photographie |scène |vue )?(montre|présente|représente|est|contient|dépeint|met en scène)" +
      "|on (voit|peut voir|aperçoit)|il y a|voici" +
      ")[:,]?\\s+").r

  /** Turn a raw model answer into a stored caption, or `None` when it is unusable.
    *
    *   - normalises whitespace (incl. NBSP), strips a leading "The image shows " style preamble
    *   - rejects non-Latin output, number / id / coordinate hallucinations, token-repetition
    *     loops, and one-or-two-word fragments - all failure modes small vision models fall into
    *   - keeps only the first sentence and caps the length
    */
  private[processor] def tidy(raw: String): Option[String] = {
    val collapsed   = raw.replaceAll("[\\p{Z}\\s]+", " ").trim.replaceFirst("^[\\p{Z}\\s\\p{Punct}]+", "")
    val depreambled = preamble.replaceFirstIn(collapsed, "")

    def rejected: Boolean = {
      val letters   = depreambled.count(_.isLetter)
      val nonLatin  = depreambled.count(c => c.isLetter && Character.UnicodeScript.of(c) != Character.UnicodeScript.LATIN)
      val digitsSym = depreambled.count(c => c.isDigit || "[]{}()=/_|<>~^*".contains(c))
      val words     = depreambled.split("\\s+").count(_.exists(_.isLetter))
      val distinct  = depreambled.iterator.filterNot(_.isWhitespace).toSet.size
      depreambled.isEmpty ||
      letters < 3 ||
      words < 4 ||                                                             // "Ids/sa_14961"
      nonLatin.toDouble / letters > 0.15 ||                                    // Thai / CJK / etc.
      digitsSym > letters ||                                                   // "Ids = [0.39, 0.42, 1.0]"
      (depreambled.length > 120 && distinct < math.max(6, depreambled.length / 12)) // repetition loop
    }

    if (rejected) None
    else {
      val firstSentence = depreambled.split("(?<=[.!?])\\s", 2).headOption.getOrElse(depreambled).trim
      val bounded       = (if (firstSentence.nonEmpty) firstSentence else depreambled).take(maxCaptionChars).trim
      val capitalised   = if (bounded.isEmpty) bounded else bounded.head.toUpper.toString + bounded.tail
      Option(capitalised).filter(_.nonEmpty)
    }
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
                         // model / server failures must not stop a batch: log the real reason and move on
                         .tapError(err => ZIO.logWarning(s"caption failed: ${err.getMessage}"))
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
