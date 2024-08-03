package fr.janalyse.sotohp.model

import wvlet.airframe.ulid.ULID

case class FaceId(
  id: ULID
) extends AnyVal {
  override def toString(): String = id.toString
}

case class DetectedFace(
  faceId: FaceId,
  someoneId: Option[SomeoneId],
  box: BoundingBox
)

case class PhotoFaces(
  faces: List[DetectedFace],
  count: Int
)
