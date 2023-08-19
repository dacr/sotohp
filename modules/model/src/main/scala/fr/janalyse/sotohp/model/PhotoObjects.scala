package fr.janalyse.sotohp.model

case class DetectedObject(
  name: String,
  box: BoundingBox
)

case class PhotoObjects(
  objects: List[DetectedObject]
)
