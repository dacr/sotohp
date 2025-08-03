package fr.janalyse.sotohp.processor.model

import fr.janalyse.sotohp.model.{Original, OriginalId}

case class FaceFeatures(
  faceId: FaceId,
  box: BoundingBox,
  features: Array[Float]
)

case class OriginalFaceFeatures(
  original: Original,
  status: ProcessedStatus,
  features: List[FaceFeatures]
) extends ProcessorResult
