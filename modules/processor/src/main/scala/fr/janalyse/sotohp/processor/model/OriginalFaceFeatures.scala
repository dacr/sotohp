package fr.janalyse.sotohp.processor.model

import fr.janalyse.sotohp.model.{FaceFeatures, FaceId, Original, OriginalId}

case class OriginalFaceFeatures(
  original: Original,
  status: ProcessedStatus,
  features: List[FaceFeatures]
) extends ProcessorResult
