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
    test("allocate reads the ambient configuration") {
      for {
        configured <- CaptionerConfig.config
        processor  <- CaptionProcessor.allocate()
      } yield assertTrue(processor.enabled == configured.enabled)
    },
    test("tidy cleans, de-preambles, first-sentences and rejects model garbage") {
      val processor = processorWith(enabled = false)
      assertTrue(
        processor.tidy("  \n The image shows a red barn in a field. It is sunny.").contains("A red barn in a field."),
        processor.tidy("Cette photo montre un chat assis sur une table en bois.").contains("Un chat assis sur une table en bois."),
        processor.tidy("- a cat sitting on a wooden table").contains("A cat sitting on a wooden table"),
        processor.tidy(" Une foret d'arbres couverts de neige un jour ensoleille.").contains("Une foret d'arbres couverts de neige un jour ensoleille."),
        // rejections (unusable model output)
        processor.tidy(loopText).isEmpty,                     // repetition loop
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
