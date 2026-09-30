package fr.janalyse.sotohp.processor.model

import fr.janalyse.sotohp.model.{FaceFeatures, FaceId, Original, OriginalId}

/** @param model
  *   the engine/model tag of the processor that computed these features (`None` = historical DJL
  *   model) - lets a backfill tell which originals are already done with the current model.
  */
case class OriginalFaceFeatures(
  original: Original,
  status: ProcessedStatus,
  features: List[FaceFeatures],
  model: Option[String] = None
) extends ProcessorResult
