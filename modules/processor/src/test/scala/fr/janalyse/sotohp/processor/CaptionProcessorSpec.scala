package fr.janalyse.sotohp.processor

import fr.janalyse.sotohp.core.OriginalBuilder.originalFromFile
import zio.*
import zio.test.*

object CaptionProcessorSpec extends BaseSpecDefault with TestDatasets {

  def suiteCaption = suite("Caption processor")(
    test("disabled captioner is a fast no-op that yields an unsuccessful, empty caption") {
      for {
        original  <- ZIO.from(originalFromFile(datasetClassesFakeStore, datasetClassesFileLakeForest))
        processor <- CaptionProcessor.allocate()
        result    <- processor.caption(original)
      } yield assertTrue(
        // reference.conf ships `captioner.enabled = false`
        !processor.enabled,
        !result.status.successful,
        result.caption.isEmpty,
        result.original.id == original.id
      )
    }
  )

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suiteCaption @@ TestAspect.sequential

}
