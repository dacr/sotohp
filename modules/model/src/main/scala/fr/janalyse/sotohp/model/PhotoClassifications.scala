package fr.janalyse.sotohp.model

case class DetectedClassification(
  name: String
)

case class PhotoClassifications(
  classifications: List[DetectedClassification]
)
