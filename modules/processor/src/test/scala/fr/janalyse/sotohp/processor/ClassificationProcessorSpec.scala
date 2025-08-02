package fr.janalyse.sotohp.processor

import fr.janalyse.sotohp.core.OriginalBuilder
import fr.janalyse.sotohp.core.OriginalBuilder.originalFromFile
import wvlet.airframe.ulid.ULID
import zio.*
import zio.lmdb.LMDB
import zio.test.*

object ClassificationProcessorSpec extends BaseSpecDefault with TestDatasets {

  def suiteClassification = suite("Classification processor")(
    test("standard scenario") {
      for {
        original1 <- ZIO.from(originalFromFile(datasetClassesFakeStore, datasetClassesFileLakeForest))
        original2 <- ZIO.from(originalFromFile(datasetClassesFakeStore, datasetClassesFileMountain))
        original3 <- ZIO.from(originalFromFile(datasetClassesFakeStore, datasetClassesFileSeacoast))
        original4 <- ZIO.from(originalFromFile(datasetClassesFakeStore, datasetClassesFileSkiWinter))
        processor <- ZIO.attempt(ClassificationProcessor.allocate())
        result1   <- processor.classify(original1)
        names1     = result1.classifications.map(_.name)
        result2   <- processor.classify(original2)
        names2     = result2.classifications.map(_.name)
        result3   <- processor.classify(original3)
        names3     = result3.classifications.map(_.name)
        result4   <- processor.classify(original4)
        names4     = result4.classifications.map(_.name)
      } yield assertTrue(
        result1.successful,
        names1.size == 0,
        //names1.contains("lakeside"),
        result2.successful,
        names2.size == 1,
        names2.contains("alp"),
        result3.successful,
        names3.size == 4,
        names3.contains("coast"),
        result4.successful,
        names4.size == 1,
        names4.contains("alp")
      )
    }
  )

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suiteClassification @@ TestAspect.sequential

}
