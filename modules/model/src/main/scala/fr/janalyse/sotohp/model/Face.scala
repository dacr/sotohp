package fr.janalyse.sotohp.model

import fr.janalyse.sotohp.model.{BoundingBox, FaceId, FacePath, OriginalId, PersonId}

import java.time.OffsetDateTime

case class Face(
  faceId: FaceId,
  originalId: OriginalId,
  box: BoundingBox,
  identifiedPersonId: Option[PersonId],
  inferredIdentifiedPersonId: Option[PersonId],
  inferredIdentifiedPersonConfidence: Option[Double],
  timestamp: OffsetDateTime,
  path: FacePath
)
