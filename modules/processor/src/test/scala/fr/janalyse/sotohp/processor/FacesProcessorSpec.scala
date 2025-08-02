package fr.janalyse.sotohp.processor

import fr.janalyse.sotohp.core.OriginalBuilder
import fr.janalyse.sotohp.core.OriginalBuilder.originalFromFile
import wvlet.airframe.ulid.ULID
import zio.*
import zio.lmdb.LMDB
import zio.test.*

object FacesProcessorSpec extends BaseSpecDefault with TestDatasets {

  def suiteFaces = suite("Faces processor")(
    test("standard scenario") {
      for {
        original1 <- ZIO.from(originalFromFile(datasetFacesFakeStore, datasetFacesFileMondement))
        original2 <- ZIO.from(originalFromFile(datasetFacesFakeStore, datasetFacesFileMariage))
        processor <- ZIO.attempt(FacesProcessor.allocate())
        result1   <- processor.extractFaces(original1)
        result2   <- processor.extractFaces(original2)
      } yield assertTrue(
        result1.successful,
        result1.faces.size == 8,
        result2.successful,
        result2.faces.size == 27
      )
    }
  )

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suiteFaces @@ TestAspect.sequential

}
