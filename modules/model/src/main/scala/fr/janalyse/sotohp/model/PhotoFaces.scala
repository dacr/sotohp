package fr.janalyse.sotohp.model

case class DetectedFace(
  someoneId: Option[SomeoneId],
  box: BoundingBox,
  features: Option[List[Float]]
)

case class PhotoFaces(
  faces: List[DetectedFace],
  count: Int
)
