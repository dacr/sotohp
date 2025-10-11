package fr.janalyse.sotohp.processor

import fr.janalyse.sotohp.core.OriginalBuilder
import fr.janalyse.sotohp.core.OriginalBuilder.originalFromFile
import wvlet.airframe.ulid.ULID
import zio.Scope
import zio.*
import zio.lmdb.LMDB
import zio.test.*

object ObjectsDetectionProcessorSpec extends BaseSpecDefault with TestDatasets {

  def suiteObjectsDetection = suite("Objects detection processor")(
    test("standard scenario") {
      for {
        original1 <- ZIO.from(originalFromFile(datasetObjectsFakeStore, datasetObjectsFileMixtureOfObjects))
        original2 <- ZIO.from(originalFromFile(datasetObjectsFakeStore, datasetObjectsFilePersonBicycle))
        processor <- ObjectsDetectionProcessor.allocate()
        result1   <- processor.extractObjects(original1)
        result2   <- processor.extractObjects(original2)
      } yield assertTrue(
        result1.status.successful,
        result1.objects.map(_.name).size == 9,
        result1.objects.map(_.name).contains("scissors"),
        result1.objects.map(_.name).contains("apple"),
        result2.status.successful,
        result2.objects.map(_.name).size == 2,
        result2.objects.map(_.name).contains("person")
      )
    }
  )

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suiteObjectsDetection @@ TestAspect.sequential @@ TestAspect.ignore

}
