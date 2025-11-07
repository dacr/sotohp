package fr.janalyse.sotohp.processor.model

import fr.janalyse.sotohp.model.{Original, OriginalId}

case class OriginalFaces(
  original: Original,
  status: ProcessedStatus,
  faces: List[DetectedFace]
) extends ProcessorResult
