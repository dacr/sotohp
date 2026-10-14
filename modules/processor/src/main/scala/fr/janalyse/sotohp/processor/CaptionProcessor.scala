package fr.janalyse.sotohp.processor

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import fr.janalyse.sotohp.core.{CoreIssue, LogContext}
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
// The Ollama server could not be used at all (unreachable, timeout, HTTP error, broken stream). It
// says nothing about the photo, so callers must not record it as a caption attempt.
case class CaptionServiceUnavailableIssue(message: String, err: Throwable) extends CaptionIssue(s"$message: ${err.getMessage}", Some(err))

// Ollama /api/generate wire types (only the fields we use).
private case class OllamaOptions(temperature: Double, num_predict: Int, repeat_penalty: Double)
private case class OllamaGenerateRequest(model: String, prompt: String, images: List[String], stream: Boolean, options: OllamaOptions)
// One line of the streamed response, or a `{"error": "..."}` line.
private case class OllamaStreamChunk(response: String = "", done: Boolean = false, error: String = "")
private object OllamaGenerateRequest { given JsonValueCodec[OllamaGenerateRequest] = JsonCodecMaker.make }
private object OllamaStreamChunk     { given JsonValueCodec[OllamaStreamChunk]     = JsonCodecMaker.make }

// OpenAI-compatible /v1/chat/completions wire types (only the fields we use), served by LocalAI,
// vLLM, llama.cpp's llama-server and Ollama itself. `repeat_penalty` is not part of the OpenAI
// schema, but LocalAI and llama.cpp honour it and the others ignore unknown fields.
private case class OpenAiImageUrl(url: String)
private case class OpenAiContentPart(`type`: String, text: Option[String] = None, image_url: Option[OpenAiImageUrl] = None)
private case class OpenAiMessage(role: String, content: List[OpenAiContentPart])
private case class OpenAiChatRequest(model: String, messages: List[OpenAiMessage], stream: Boolean, temperature: Double, max_tokens: Int, repeat_penalty: Double)
private case class OpenAiDelta(content: Option[String] = None)
private case class OpenAiChoice(delta: OpenAiDelta = OpenAiDelta())
private case class OpenAiError(message: String = "")
// One `data: {...}` event of the streamed response, or an `{"error": {...}}` object.
private case class OpenAiStreamChunk(choices: List[OpenAiChoice] = Nil, error: Option[OpenAiError] = None)
private object OpenAiChatRequest { given JsonValueCodec[OpenAiChatRequest] = JsonCodecMaker.make }
private object OpenAiStreamChunk { given JsonValueCodec[OpenAiStreamChunk] = JsonCodecMaker.make }

/** Image-to-text: asks a local vision model to describe a photo, through either Ollama's native
  * API or the OpenAI-compatible one (`config.api`).
  *
  * A plain HTTP client, not a DJL predictor. When `config.enabled` is false every call is a
  * fast no-op returning an unsuccessful [[OriginalCaption]] (so nothing is stored and the step
  * retries once the captioner is turned on).
  *
  * The request is streamed and the chunks reassembled here: Ollama's non-streaming responses
  * drop the first output token for some models, which streaming avoids.
  */
class CaptionProcessor(config: CaptionerConfig) extends Processor {

  val enabled: Boolean = config.enabled

  // Hard cap on the description part of a stored caption. A well-behaved model stays well under
  // this; anything longer is cut back to the whole sentences that fit by `tidy`.
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

  private def requestCaption(imageBase64: String): IO[CoreIssue, String] =
    if (config.api == CaptionerConfig.openaiApi) requestCaptionOpenAi(imageBase64)
    else requestCaptionOllama(imageBase64)

  private def requestCaptionOpenAi(imageBase64: String): IO[CoreIssue, String] = {
    val payload = OpenAiChatRequest(
      model = config.model,
      messages = List(
        OpenAiMessage(
          role = "user",
          content = List(
            OpenAiContentPart(`type` = "text", text = Some(config.prompt)),
            OpenAiContentPart(`type` = "image_url", image_url = Some(OpenAiImageUrl(s"data:image/jpeg;base64,$imageBase64")))
          )
        )
      ),
      stream = true,
      // same generation caps as the Ollama path, see there
      temperature = 0d,
      max_tokens = 120,
      repeat_penalty = 1.3d
    )
    val request = HttpRequest
      .newBuilder()
      .uri(URI.create(s"${config.baseUrl.stripSuffix("/")}/v1/chat/completions"))
      .timeout(Duration.ofSeconds(config.timeoutSeconds.toLong.max(1)))
      .header("Content-Type", "application/json")
      .header("Accept", "text/event-stream")
      .POST(HttpRequest.BodyPublishers.ofByteArray(writeToArray(payload)))
      .build()

    ZIO
      .attemptBlocking {
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines())
        if (response.statusCode() / 100 != 2) {
          val body = response.body().iterator().asScala.take(5).mkString(" ")
          throw CaptionGeneralIssue(s"server returned HTTP ${response.statusCode()}: ${body.take(300)}")
        }
        val sb = new StringBuilder()
        response.body().iterator().asScala.foreach(line => CaptionProcessor.openAiStreamLineText(line).foreach(sb.append))
        sb.toString
      }
      .mapError(err => CaptionServiceUnavailableIssue(s"OpenAI-compatible request to ${config.baseUrl} failed", err))
  }

  private def requestCaptionOllama(imageBase64: String): IO[CoreIssue, String] = {
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
      .mapError(err => CaptionServiceUnavailableIssue(s"Ollama request to ${config.baseUrl} failed", err))
  }

  // Strips a leading "The image shows " / "Cette photo montre " style preamble, EN or FR.
  private val preamble =
    ("(?i)^(" +
      "(the |this |it )?(image |picture |photo |photograph |scene )?(shows|depicts|features|contains|portrays|is(?: of| a| an)?)" +
      "|(cette |la |l'|une |ce |le )?(image |photo |photographie |scène |vue )?(montre|présente|représente|est|contient|dépeint|met en scène)" +
      "|on (voit|peut voir|aperçoit)|il y a|voici" +
      ")[:,]?\\s+").r

  // A quoted passage - how the prompt asks the model to transcribe text visible in the photo.
  private val quotedPattern = "«[^«»]*»|“[^“”]*”|\"[^\"]*\"".r
  // Empty quotes, which the model writes when there is no text to transcribe, with the space before them.
  private val emptyQuotes   = "[\\p{Z}\\s]*(«[\\p{Z}\\s]*»|“[\\p{Z}\\s]*”|\"[\\p{Z}\\s]*\")".r
  // "Aucun texte n'est visible sur la photo." - says nothing about the photo.
  private val noTextSentence =
    "(?i)^(aucun texte|il n'y a (pas de|aucun) texte|pas de texte|le texte visible|no (visible )?text|there is no (visible )?text).*".r
  // Room kept for the transcriptions appended after the description.
  private val maxTranscriptionChars = 200

  /** The sentences of `text` - split after a `.`, `!` or `?` followed by a space, ignoring those
    * inside a quoted passage so that a transcription is never cut in the middle. A quote left open
    * (generation cut by the token cap) runs to the end of the text.
    */
  private def sentencesOf(text: String): List[String] = {
    val sentences             = List.newBuilder[String]
    var closing: Option[Char] = None // the quote closing character expected, while inside a quote
    var start                 = 0
    var i                     = 0
    while (i < text.length) {
      val c = text.charAt(i)
      closing match {
        case Some(expected) => if (c == expected) closing = None
        case None           =>
          c match {
            case '«'                                                                        => closing = Some('»')
            case '“'                                                                        => closing = Some('”')
            case '"'                                                                        => closing = Some('"')
            case '.' | '!' | '?' if i + 1 == text.length || text.charAt(i + 1).isWhitespace =>
              sentences += text.substring(start, i + 1).trim
              start = i + 1
            case _                                                                          =>
          }
      }
      i += 1
    }
    sentences += text.substring(start).trim
    sentences.result().filter(_.nonEmpty)
  }

  // Ends like a finished sentence (or a finished quote) - anything else was cut by the token cap.
  private def complete(sentence: String): Boolean = ".!?…»”\"".contains(sentence.last)

  /** Turn a raw model answer into a stored caption, or `None` when it is unusable.
    *
    *   - normalises whitespace (incl. NBSP), strips a leading "The image shows " style preamble
    *   - rejects non-Latin output, number / id / coordinate hallucinations, token-repetition
    *     loops, and one-or-two-word fragments - all failure modes small vision models fall into.
    *     Quoted passages are left out of these checks : a transcription of a sign may well be in
    *     another script, or mostly digits
    *   - keeps the description's sentences, as many whole ones as the length cap allows, minus an
    *     unfinished last one (generation cut by the token cap) and the sentences that say nothing
    *     about the photo ("Aucun texte n'est visible.", "Le texte visible est :" before empty quotes)
    *   - then appends the text the model transcribed from the photo : the non-empty quoted passages
    *     the description doesn't already hold, which usually come after it, on their own line.
    *     Empty quotes, which the model writes when there is no text to transcribe, are dropped.
    */
  private[processor] def tidy(raw: String): Option[String] = {
    val collapsed   = raw.replaceAll("[\\p{Z}\\s]+", " ").trim.replaceFirst("^[\\p{Z}\\s\\p{Punct}&&[^«“\"]]+", "")
    val depreambled = preamble.replaceFirstIn(collapsed, "")
    val unquoted    = quotedPattern.replaceAllIn(depreambled, " ").replaceAll("\\s+", " ").trim

    def rejected: Boolean = {
      val letters   = unquoted.count(_.isLetter)
      val nonLatin  = unquoted.count(c => c.isLetter && Character.UnicodeScript.of(c) != Character.UnicodeScript.LATIN)
      val digitsSym = unquoted.count(c => c.isDigit || "[]{}()=/_|<>~^*".contains(c))
      val words     = unquoted.split("\\s+").count(_.exists(_.isLetter))
      val distinct  = unquoted.iterator.filterNot(_.isWhitespace).toSet.size
      // Repetition loops. A natural sentence uses ~35 distinct characters whatever its length, so
      // the character threshold must not grow with the length (it used to, and rejected every
      // legitimate caption past ~420 characters) ; a loop of a whole sentence keeps a natural
      // alphabet but repeats its words.
      val wordList  = unquoted.toLowerCase.split("[^\\p{L}\\p{N}]+").filter(_.nonEmpty)
      val charLoop  = unquoted.length > 120 && distinct < math.max(6, math.min(20, unquoted.length / 12))
      val wordLoop  = wordList.length >= 20 && wordList.distinct.length.toDouble / wordList.length < 0.4
      unquoted.isEmpty ||
      letters < 3 ||
      words < 4 ||                                                             // "Ids/sa_14961"
      nonLatin.toDouble / letters > 0.15 ||                                    // Thai / CJK / etc.
      digitsSym > letters ||                                                   // "Ids = [0.39, 0.42, 1.0]"
      charLoop || wordLoop                                                     // repetition loop
    }

    val cleaned   = emptyQuotes.replaceAllIn(depreambled, "").trim
    val sentences = sentencesOf(cleaned)
    // An unfinished last sentence was cut short - by the token cap, or because the server ran out of
    // context (concurrent requests sharing it, which the server still reports as a normal "stop").
    // It goes even when it is the only one : storing "Un homme âgé, vêtu d'un costume formel" as a
    // caption is worse than recording a failed attempt, which `--retry` redoes.
    val finished  = if (sentences.nonEmpty && !complete(sentences.last)) sentences.init else sentences

    if (rejected || finished.isEmpty) None
    else {
      val describing     = finished.filter { sentence =>
        quotedPattern.replaceAllIn(sentence, "").exists(_.isLetter) && // not just a transcription
        !sentence.endsWith(":") &&                                     // "Le texte visible est :" whose quotes were empty
        noTextSentence.findFirstIn(sentence).isEmpty
      }
      // whole sentences, as many as fit - at least the first one, cut if it alone is too long
      val description    = describing match {
        case Nil           => finished.headOption.getOrElse(cleaned).take(maxCaptionChars).trim
        case first :: more =>
          more
            .foldLeft((List(first.take(maxCaptionChars).trim), false)) { case ((kept, full), sentence) =>
              if (full || (kept :+ sentence).mkString(" ").length > maxCaptionChars) (kept, true) else (kept :+ sentence, false)
            }
            ._1
            .mkString(" ")
      }
      val transcriptions = quotedPattern
        .findAllIn(cleaned)
        .map(_.trim)
        .filter(quote => quote.drop(1).dropRight(1).exists(_.isLetterOrDigit))
        .filterNot(quote => description.contains(quote))
        .toList
        .distinct
      // whole passages only, as many as fit
      val appended       = transcriptions
        .foldLeft(List.empty[String]) { (kept, quote) =>
          if ((kept :+ quote).mkString(" ").length <= maxTranscriptionChars) kept :+ quote else kept
        }
        .mkString(" ")
      val caption        = (if (appended.isEmpty) description else s"$description $appended").trim
      val capitalised    = if (caption.isEmpty) caption else caption.head.toUpper.toString + caption.tail
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
                         .tap {
                           // key/values are rendered unquoted : quote the sentence to keep the log line readable
                           case Some(text) => ZIO.logInfo("Caption generated") @@ LogContext.kv("caption" -> s"\"${text.replace("\"", "'")}\"")
                           case None       => ZIO.logInfo("Caption empty")
                         }
                         // A photo-level failure (unreadable image) must not stop a batch: log it
                         // and record the attempt as unsuccessful. An unusable server is not the
                         // photo's fault, so it is propagated and nothing gets recorded.
                         .tapError(err => ZIO.logWarning("Caption failed") @@ LogContext.kv("error" -> err.getMessage))
                         .map(Some(_))
                         .catchSome { case _: CaptionRequestIssue | _: CaptionGeneralIssue => ZIO.none }
                         .map(_.flatten)
          status    = ProcessedStatus(successful = mayBeText.isDefined, timestamp = now)
        } yield OriginalCaption(original, status, mayBeText)

    logic
      @@ LogContext.original(original)
  }
}

object CaptionProcessor {
  def allocate(): IO[CaptionIssue, CaptionProcessor] =
    CaptionerConfig.config
      .mapError(err => CaptionGeneralIssue(s"Unable to load captioner configuration: $err"))
      .map(CaptionProcessor(_))

  /** The text carried by one line of an OpenAI-style server-sent-events stream : `data: {...}`
    * events yield their delta content, everything else (blank separators, `data: [DONE]`, SSE
    * comments and `event:` lines) yields nothing. An error event, or a bare JSON error body some
    * servers send instead of an event, throws.
    */
  private[processor] def openAiStreamLineText(line: String): Option[String] = {
    val trimmed = line.trim
    val payload =
      if (trimmed.startsWith("data:")) Some(trimmed.stripPrefix("data:").trim)
      else if (trimmed.startsWith("{")) Some(trimmed)
      else None
    payload.filter(p => p.nonEmpty && p != "[DONE]").flatMap { json =>
      val chunk = readFromString[OpenAiStreamChunk](json)
      chunk.error.foreach(err => throw CaptionGeneralIssue(s"server error: ${err.message}"))
      val text = chunk.choices.flatMap(_.delta.content).mkString
      Option(text).filter(_.nonEmpty)
    }
  }

}
