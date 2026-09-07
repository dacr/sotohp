package fr.janalyse.sotohp.processor.model

import fr.janalyse.sotohp.model.{MediaFeatures, Original}

/** Result of computing a whole-image embedding for a photo.
  *
  * @param features
  *   `None` when the model run failed (see `status.successful`)
  */
case class OriginalMediaFeatures(
  original: Original,
  status: ProcessedStatus,
  features: Option[MediaFeatures]
) extends ProcessorResult
