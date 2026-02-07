package fr.janalyse.sotohp.processor.model

import fr.janalyse.sotohp.model.{Face, Original, OriginalId}

case class OriginalFaces(
  original: Original,
  status: ProcessedStatus,
  faces: List[Face]
) extends ProcessorResult
