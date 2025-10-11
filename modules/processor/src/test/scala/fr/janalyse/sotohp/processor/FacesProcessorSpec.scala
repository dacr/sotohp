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
        processor <- FacesProcessor.allocate()
        result1   <- processor.extractFaces(original1)
        result2   <- processor.extractFaces(original2)
      } yield assertTrue(
        result1.status.successful,
        result1.faces.size == 9,
        result2.status.successful,
        result2.faces.size == 27
      )
    }
  )

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suiteFaces @@ TestAspect.sequential @@ TestAspect.ignore

}
