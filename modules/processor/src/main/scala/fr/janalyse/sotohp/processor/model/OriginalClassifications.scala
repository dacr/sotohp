package fr.janalyse.sotohp.processor.model

import fr.janalyse.sotohp.model.Original

case class DetectedClassification(
  name: String,
  probability: Double
)

case class OriginalClassifications(
  original: Original,
  status: ProcessedStatus,
  classifications: List[DetectedClassification]
) extends ProcessorResult
