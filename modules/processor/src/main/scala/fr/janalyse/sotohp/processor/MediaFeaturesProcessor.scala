package fr.janalyse.sotohp.processor

import ai.djl.Application
import ai.djl.inference.Predictor
import ai.djl.modality.cv.{Image, ImageFactory}
import ai.djl.repository.zoo.{Criteria, ZooModel}
import fr.janalyse.sotohp.core.CoreIssue
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.processor.model.*
import zio.*
import zio.ZIOAspect.*

trait MediaFeaturesIssue(message: String, mayBeErr: Option[Throwable]) extends Exception with CoreIssue
case class MediaFeaturesGeneralIssue(message: String)                 extends MediaFeaturesIssue(message, None)
case class MediaFeaturesExtractIssue(message: String, err: Throwable) extends MediaFeaturesIssue(message, Some(err))

/** Computes one whole-image embedding per photo, used for visual similarity search and
  * clustering of similar photos.
  *
  * Same shape as [[FaceFeaturesProcessor]]: a single not-thread-safe DJL predictor,
  * `Image => Array[Float]`, run against the cached 1920px normalized JPEG.
  */
class MediaFeaturesProcessor(predictor: Predictor[Image, Array[Float]]) extends Processor {

  override def close(): Unit = {
    predictor.close()
  }

  /** Extract the embedding vector for a single photo.
    *
    * On any failure the returned `OriginalMediaFeatures` has `status.successful = false`
    * and `features = None` (mirrors how `FaceFeaturesProcessor` swallows failures so a
    * batch sync is never stopped by one bad photo).
    */
  def extractMediaFeatures(original: Original): IO[CoreIssue, OriginalMediaFeatures] = {
    val logic = for {
      now         <- Clock.currentDateTime
      mayBeVector <- loadOriginalBestInputFileForProcessors(original)
                       .flatMap(image =>
                         ZIO.attemptBlocking(ImageFactory.getInstance().fromImage(image))
                       )
                       .flatMap(djlImage => ZIO.attemptBlocking(predictor.predict(djlImage)))
                       .mapError(err => MediaFeaturesExtractIssue("Couldn't compute media features", err))
                       .logError("Media features issue")
                       .option
      status       = ProcessedStatus(successful = mayBeVector.isDefined, timestamp = now)
      features     = mayBeVector.map(vector => MediaFeatures(originalId = original.id, features = vector))
    } yield OriginalMediaFeatures(original, status, features)

    logic
      @@ annotated("originalId" -> original.id.asString)
      @@ annotated("originalPath" -> original.absoluteMediaPath.toString)
  }

}

object MediaFeaturesProcessor {

  // torchvision ResNet-18 (ImageNet), traced, hosted by DJL. A custom translator
  // returns the raw 1000-d logit vector (L2-normalised) rather than class labels.
  // Swap this single Criteria to change the embedding model.
  private def criteria: Criteria[Image, Array[Float]] =
    Criteria
      .builder()
      .optApplication(Application.CV.IMAGE_CLASSIFICATION)
      .setTypes(classOf[Image], classOf[Array[Float]])
      .optModelUrls("djl://ai.djl.pytorch/resnet")
      .optFilter("layers", "18")
      .optFilter("dataset", "imagenet")
      .optTranslator(new ImageEmbeddingExtraction.ImageEmbeddingTranslator())
      .optEngine("PyTorch")
      .build()

  def allocate(): IO[MediaFeaturesExtractIssue, MediaFeaturesProcessor] = {
    for {
      semaphore <- Semaphore.make(1)
      logic      = ZIO
                     .attemptBlocking {
                       val model: ZooModel[Image, Array[Float]] = criteria.loadModel()
                       val predictor                            = model.newPredictor() // not thread safe !
                       MediaFeaturesProcessor(predictor)
                     }
                     .logError("Media features processor allocation issue")
                     .mapError(err => MediaFeaturesExtractIssue("Unable to allocate media features processor", err))
      result    <- semaphore.withPermit(logic)
    } yield result
  }

}
