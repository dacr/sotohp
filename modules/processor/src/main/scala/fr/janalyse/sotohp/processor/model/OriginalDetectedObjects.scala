package fr.janalyse.sotohp.processor.model

import fr.janalyse.sotohp.model.Original

case class DetectedObject(
  name: String,
  box: BoundingBox
)

case class OriginalDetectedObjects(
  original: Original,
  successful: Boolean,
  objects: List[DetectedObject]
)
