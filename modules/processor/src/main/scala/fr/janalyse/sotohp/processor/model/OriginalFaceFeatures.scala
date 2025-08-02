package fr.janalyse.sotohp.processor.model

import fr.janalyse.sotohp.model.{Original, OriginalId}

case class FaceFeatures(
  faceId: FaceId,
  box: BoundingBox,
  features: Array[Float]
)

case class OriginalFaceFeatures(
  original: Original,
  successful: Boolean,
  features: List[FaceFeatures]
)
