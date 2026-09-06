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
) {

  /** True when this face still carries some of the inference bookkeeping. */
  def hasInferredIdentification: Boolean =
    inferredIdentifiedPersonId.isDefined ||
      inferredIdentifiedPersonConfidence.isDefined ||
      inferredTimestamp.isDefined ||
      inferredIgnore.isDefined

  /** Drops all the inference bookkeeping. Once a human has identified the face, the guess the
    * inference recorded is settled and must not survive : kept around it would resurrect in the
    * review queue as soon as the identification is removed, and a stale `inferredIgnore` would go
    * on vetoing the inference of other faces of that very person.
    */
  def withoutInferredIdentification: Face =
    copy(
      inferredIdentifiedPersonId = None,
      inferredIdentifiedPersonConfidence = None,
      inferredTimestamp = None,
      inferredIgnore = None
    )
}
