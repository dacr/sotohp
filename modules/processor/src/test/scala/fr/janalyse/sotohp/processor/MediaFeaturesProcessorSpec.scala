package fr.janalyse.sotohp.processor

import fr.janalyse.sotohp.core.OriginalBuilder.originalFromFile
import zio.*
import zio.test.*

object MediaFeaturesProcessorSpec extends BaseSpecDefault with TestDatasets {

  private def cosine(a: Array[Float], b: Array[Float]): Double = {
    var dot = 0d
    var i   = 0
    while (i < a.length) { dot += a(i).toDouble * b(i).toDouble; i += 1 }
    dot
  }

  private def l2(a: Array[Float]): Double = math.sqrt(cosine(a, a))

  def suiteMediaFeatures = suite("Media features processor")(
    test("computes a stable normalised whole-image embedding") {
      for {
        forest    <- ZIO.from(originalFromFile(datasetClassesFakeStore, datasetClassesFileLakeForest))
        mountain  <- ZIO.from(originalFromFile(datasetClassesFakeStore, datasetClassesFileMountain))
        processor <- MediaFeaturesProcessor.allocate()
        r1        <- processor.extractMediaFeatures(forest)
        r1bis     <- processor.extractMediaFeatures(forest)
        r2        <- processor.extractMediaFeatures(mountain)
        v1         = r1.features.get.features
        v1bis      = r1bis.features.get.features
        v2         = r2.features.get.features
      } yield assertTrue(
        r1.status.successful && r2.status.successful,
        r1.features.isDefined && r2.features.isDefined,
        v1.length > 0,
        v1.length == v2.length,
        v1.forall(f => !f.isNaN && !f.isInfinite),
        math.abs(l2(v1) - 1d) < 1e-3,                       // translator L2-normalises the output
        math.abs(cosine(v1, v1bis) - 1d) < 1e-4,            // deterministic for the same image
        cosine(v1, v2) < 0.999                              // two different scenes are not identical
      )
    } //@@ TestAspect.ignore
  )

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suiteMediaFeatures @@ TestAspect.sequential

}
