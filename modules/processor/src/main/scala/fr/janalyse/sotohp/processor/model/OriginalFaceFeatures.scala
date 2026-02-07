package fr.janalyse.sotohp.processor.model

import fr.janalyse.sotohp.model.{FaceId, Original, OriginalId}

case class FaceFeatures(
  faceId: FaceId,
  features: Array[Float]
)

case class OriginalFaceFeatures(
  original: Original,
  status: ProcessedStatus,
  features: List[FaceFeatures]
) extends ProcessorResult
