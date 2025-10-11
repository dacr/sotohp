package fr.janalyse.sotohp.processor

import fr.janalyse.sotohp.core.OriginalBuilder
import fr.janalyse.sotohp.core.OriginalBuilder.originalFromFile
import wvlet.airframe.ulid.ULID
import zio.*
import zio.lmdb.LMDB
import zio.test.*

object MiniaturizeProcessorSpec extends BaseSpecDefault with TestDatasets {

  def suiteFaces = suite("Faces processor")(
    test("standard scenario") {
      for {
        original  <- ZIO.from(originalFromFile(datasetFacesFakeStore, datasetFacesFileMondement))
        processor <- FacesProcessor.allocate()
        result    <- processor.extractFaces(original)
      } yield assertTrue(
        result.status.successful,
        result.faces.size == 9
      )
    } @@ TestAspect.ignore
  )

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suiteFaces @@ TestAspect.sequential @@ TestAspect.ignore

}
