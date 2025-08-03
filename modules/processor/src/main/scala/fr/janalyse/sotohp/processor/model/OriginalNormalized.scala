package fr.janalyse.sotohp.processor.model

import fr.janalyse.sotohp.model.*

case class OriginalNormalized(
  original: Original,
  status: ProcessedStatus,
  dimension: Option[Dimension]
) extends ProcessorResult
