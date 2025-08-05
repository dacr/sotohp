package fr.janalyse.sotohp.processor.model

import fr.janalyse.sotohp.model.*

case class Normalized(
  dimension: Dimension,
  path: NormalizedPath
)

case class OriginalNormalized(
  original: Original,
  status: ProcessedStatus,
  normalized: Option[Normalized]
) extends ProcessorResult
