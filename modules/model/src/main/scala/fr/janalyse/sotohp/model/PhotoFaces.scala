package fr.janalyse.sotohp.model

import wvlet.airframe.ulid.ULID

case class DetectedFace(
  faceId: ULID,
  someoneId: Option[SomeoneId],
  box: BoundingBox
)

case class PhotoFaces(
  faces: List[DetectedFace],
  count: Int
)
