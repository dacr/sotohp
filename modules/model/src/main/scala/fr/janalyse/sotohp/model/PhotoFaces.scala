package fr.janalyse.sotohp.model

case class DetectedFace(
  someoneId: Option[SomeoneId],
  box: BoundingBox
)

case class PhotoFaces(
  faces: List[DetectedFace]
)
