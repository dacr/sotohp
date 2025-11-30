package fr.janalyse.sotohp.processor.model

import fr.janalyse.sotohp.model.OriginalId

import java.time.OffsetDateTime

case class DetectedFace(
  faceId: FaceId,
  originalId: OriginalId,
  box: BoundingBox,
  identifiedPersonId: Option[PersonId],
  inferredIdentifiedPersonId: Option[PersonId],
  inferredIdentifiedPersonConfidence: Option[Double],
  timestamp: OffsetDateTime,
  path: DetectedFacePath
)
