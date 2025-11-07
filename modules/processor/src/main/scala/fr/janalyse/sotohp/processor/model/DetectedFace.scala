package fr.janalyse.sotohp.processor.model

import fr.janalyse.sotohp.model.OriginalId

case class DetectedFace(
  faceId: FaceId,
  originalId: OriginalId,
  box: BoundingBox,
  identifiedPersonId: Option[PersonId],
  path: DetectedFacePath
)
