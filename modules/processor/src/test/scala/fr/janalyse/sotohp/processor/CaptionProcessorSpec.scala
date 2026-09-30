package fr.janalyse.sotohp.processor

import fr.janalyse.sotohp.core.OriginalBuilder.originalFromFile
import fr.janalyse.sotohp.processor.config.CaptionerConfig
import zio.*
import zio.test.*

object CaptionProcessorSpec extends BaseSpecDefault with TestDatasets {

  // Built here rather than through `CaptionProcessor.allocate()`: that reads the ambient
  // configuration, and a dev shell with PHOTOS_CAPTIONER_ENABLED=true would otherwise flip the
  // meaning of these tests.
  private def processorWith(enabled: Boolean): CaptionProcessor =
    CaptionProcessor(
      CaptionerConfig(
        enabled = enabled,
        api = CaptionerConfig.ollamaApi,
        baseUrl = "http://127.0.0.1:1", // never reached while disabled
        model = "test-model",
        prompt = "describe",
        timeoutSeconds = 1,
        maxImageSize = 0
      )
    )

  // A degenerate token-repetition loop (Thai syllable repeated), built from code points so the
  // source file stays ASCII.
  private val loopText: String =
    (List(0x0e23, 0x0e31, 0x0e1a, 0x0e22, 0x0e32, 0x0e21).map(_.toChar).mkString) * 80
  // A short non-Latin (Thai) phrase - "wrong language" for a French/English caption.
  private val thaiPhrase: String =
    List(0x0e23, 0x0e49, 0x0e32, 0x0e19, 0x0e21, 0x0e35, 0x0e22, 0x0e07, 0x0e2a, 0x0e27, 0x0e31, 0x0e14, 0x0020,
      0x0e1e, 0x0e17, 0x0e2d, 0x0e01, 0x0e25, 0x0e30, 0x0e04, 0x0e38, 0x0e13, 0x002e).map(_.toChar).mkString

  // Five different sentences, 480 characters - more than the 400 characters cap holds (identical ones
  // would rightly be rejected as a repetition loop).
  private val longAnswer: String =
    List(
      "Une longue rue pavée bordée de maisons anciennes aux volets colorés descend vers le port.",
      "Des passants flânent devant les vitrines des boutiques, certains tenant des sacs de courses.",
      "Au loin, les mâts des voiliers amarrés se dressent contre un ciel légèrement voilé.",
      "Une terrasse de café installée sur le trottoir accueille quelques clients attablés au soleil.",
      "Des jardinières fleuries garnissent les rebords des fenêtres des étages supérieurs de la rue."
    ).mkString(" ")

  def suiteCaption = suite("Caption processor")(
    test("a disabled captioner is a fast no-op that yields an unsuccessful, empty caption") {
      val processor = processorWith(enabled = false)
      for {
        original <- ZIO.from(originalFromFile(datasetClassesFakeStore, datasetClassesFileLakeForest))
        result   <- processor.caption(original)
      } yield assertTrue(
        !processor.enabled,
        !result.status.successful,
        result.caption.isEmpty,
        result.original.id == original.id
      )
    },
    test("an unreachable server fails the call instead of yielding an unsuccessful caption") {
      val processor = processorWith(enabled = true) // 127.0.0.1:1 refuses connections
      for {
        original <- ZIO.from(originalFromFile(datasetClassesFakeStore, datasetClassesFileLakeForest))
        result   <- processor.caption(original).exit
      } yield assertTrue(
        result.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[CaptionServiceUnavailableIssue])
      )
    },
    test("allocate reads the ambient configuration") {
      for {
        configured <- CaptionerConfig.config
        processor  <- CaptionProcessor.allocate()
      } yield assertTrue(processor.enabled == configured.enabled)
    },
    test("OpenAI-style stream lines yield their delta text and nothing else") {
      assertTrue(
        CaptionProcessor.openAiStreamLineText("""data: {"id":"x","choices":[{"index":0,"delta":{"role":"assistant","content":"Un chat"},"finish_reason":null}]}""").contains("Un chat"),
        CaptionProcessor.openAiStreamLineText("""data: {"choices":[{"delta":{"content":null},"finish_reason":"stop"}]}""").isEmpty,
        CaptionProcessor.openAiStreamLineText("data: [DONE]").isEmpty,
        CaptionProcessor.openAiStreamLineText("").isEmpty,
        CaptionProcessor.openAiStreamLineText(": keep-alive").isEmpty,
        scala.util.Try(CaptionProcessor.openAiStreamLineText("""data: {"error":{"message":"model not found","code":404}}""")).isFailure,
        scala.util.Try(CaptionProcessor.openAiStreamLineText("""{"error":{"message":"model not found"}}""")).isFailure
      )
    },
    test("tidy keeps the text the model transcribed from the photo") {
      val processor = processorWith(enabled = false)
      assertTrue(
        // answers as qwen3-vl-8b actually writes them: the transcription after the description, on its own line
        processor
          .tidy("Cette photo montre un bâtiment à colombages en bois peint en rouge et blanc. Le sol est pavé de dalles irrégulières.  \n« 145 »")
          .contains("Un bâtiment à colombages en bois peint en rouge et blanc. Le sol est pavé de dalles irrégulières. « 145 »"),
        // or in a sentence of its own, possibly empty when there is no text to transcribe
        processor
          .tidy("Vue en plongée d'un plafond à voûte en pierre, orné de nervures radiales. Le texte visible sur l'image est : « »")
          .contains("Vue en plongée d'un plafond à voûte en pierre, orné de nervures radiales."),
        processor
          .tidy("Un lac calme reflète un ciel bleu et une ligne d'arbres verdoyants.  \n« »")
          .contains("Un lac calme reflète un ciel bleu et une ligne d'arbres verdoyants."),
        // a sentence end inside a quote doesn't cut the description short
        processor
          .tidy("Un panneau indique « Défense d'entrer. Danger » près d'un portail rouillé. Le ciel est gris.")
          .contains("Un panneau indique « Défense d'entrer. Danger » près d'un portail rouillé. Le ciel est gris."),
        // already in the description : not repeated
        processor
          .tidy("Un panneau « STOP » se dresse au carrefour d'une route de campagne. On y lit « STOP ».")
          .contains("Un panneau « STOP » se dresse au carrefour d'une route de campagne. On y lit « STOP »."),
        // straight and curly quotes, several passages
        processor
          .tidy("Un vieux mur couvert d'affiches déchirées. On lit \"Liberté, égalité\" et “Bal du 14 juillet”.")
          .contains("Un vieux mur couvert d'affiches déchirées. On lit \"Liberté, égalité\" et “Bal du 14 juillet”."),
        // a transcription in another script is legitimate, not a wrong-language answer
        processor
          .tidy("Une enseigne jaune de restaurant avec un cornet de frites dessiné. « 황리단길 »")
          .contains("Une enseigne jaune de restaurant avec un cornet de frites dessiné. « 황리단길 »"),
        // a quote left open by the token cap doesn't swallow the rest
        processor
          .tidy("Une vitrine de boulangerie à l'ancienne. L'enseigne indique « Boulangerie Pâtisserie Maison fondée en")
          .contains("Une vitrine de boulangerie à l'ancienne."),
        // a sentence saying there is no text says nothing about the photo
        processor
          .tidy("Deux jeunes personnes discutent dans l'eau peu profonde d'une plage. Aucun texte n'est visible sur la photo.")
          .contains("Deux jeunes personnes discutent dans l'eau peu profonde d'une plage."),
        // an answer cut by the token cap in the middle of a sentence keeps its finished sentences
        processor
          .tidy("Cinq danseuses en robes rouges sont alignées sur une scène. Elles sont pieds nus et adoptent des poses")
          .contains("Cinq danseuses en robes rouges sont alignées sur une scène."),
        // the length cap keeps whole sentences only
        processor
          .tidy(longAnswer)
          .exists(caption => caption.length <= 400 && caption.endsWith(".") && caption.startsWith("Une longue rue"))
      )
    },
    test("tidy cleans, de-preambles and rejects model garbage") {
      val processor = processorWith(enabled = false)
      assertTrue(
        processor.tidy("  \n The image shows a red barn in a field. It is sunny.").contains("A red barn in a field. It is sunny."),
        processor.tidy("Cette photo montre un chat assis sur une table en bois.").contains("Un chat assis sur une table en bois."),
        processor.tidy("- a cat sitting on a wooden table").contains("A cat sitting on a wooden table"),
        processor.tidy(" Une foret d'arbres couverts de neige un jour ensoleille.").contains("Une foret d'arbres couverts de neige un jour ensoleille."),
        // rejections (unusable model output)
        processor.tidy(loopText).isEmpty,                     // repetition loop
        processor.tidy(List.fill(6)("Un chat noir dort sur un canapé rouge près de la fenêtre.").mkString(" ")).isEmpty, // sentence loop
        processor.tidy("un chat " * 30).isEmpty,               // short loop
        processor.tidy(thaiPhrase).isEmpty,                   // wrong (non-Latin) language
        processor.tidy("Ids = [0.39, 0.42, 1.0]").isEmpty,    // coordinate hallucination
        processor.tidy("Ids/sa_14961").isEmpty,               // id / filename fragment
        processor.tidy("   ").isEmpty
      )
    }
  )

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suiteCaption @@ TestAspect.sequential

}
