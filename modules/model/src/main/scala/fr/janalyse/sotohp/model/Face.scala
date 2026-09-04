package fr.janalyse.sotohp.model

import java.time.OffsetDateTime

case class Face(
  faceId: FaceId,
  originalId: OriginalId,
  box: BoundingBox,
  identifiedPersonId: Option[PersonId],
  inferredIdentifiedPersonId: Option[PersonId],
  inferredIdentifiedPersonConfidence: Option[Double],
  inferredTimestamp: Option[OffsetDateTime],
  inferredIgnore: Option[Boolean],
  timestamp: OffsetDateTime,
  path: FacePath
)
