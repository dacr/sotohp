package fr.janalyse.sotohp.processor.model

import fr.janalyse.sotohp.model.Original

/** Result of asking an image-to-text model to describe a photo.
  *
  * @param caption
  *   `None` when the model run failed or the captioner is disabled (see `status.successful`)
  */
case class OriginalCaption(
  original: Original,
  status: ProcessedStatus,
  caption: Option[String]
) extends ProcessorResult
