package fr.janalyse.sotohp.processor.model

import fr.janalyse.sotohp.model.{BoundingBox, Original}

case class DetectedObject(
  name: String,
  probability: Double,
  box: BoundingBox
)

case class OriginalDetectedObjects(
  original: Original,
  status: ProcessedStatus,
  objects: List[DetectedObject]
) extends ProcessorResult
