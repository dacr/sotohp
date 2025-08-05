package fr.janalyse.sotohp.processor.model

import fr.janalyse.sotohp.model.Original

case class DetectedFace(
  faceId: FaceId,
  box: BoundingBox,
  path: DetectedFacePath
)

case class OriginalFaces(
  original: Original,
  status: ProcessedStatus,
  faces: List[DetectedFace]
) extends ProcessorResult
