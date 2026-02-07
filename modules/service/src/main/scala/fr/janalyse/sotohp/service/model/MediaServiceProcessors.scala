package fr.janalyse.sotohp.service.model

import fr.janalyse.sotohp.processor.*
import zio.IO

case class MediaServiceProcessors(
  classifications: IO[ClassificationIssue, ClassificationProcessor],
  faces: IO[FacesDetectionIssue, FacesProcessor],
  faceFeatures: IO[FaceFeaturesIssue, FaceFeaturesProcessor],
  objects: IO[ObjectsDetectionIssue, ObjectsDetectionProcessor]
)
