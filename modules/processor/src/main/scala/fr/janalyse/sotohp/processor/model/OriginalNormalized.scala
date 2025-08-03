package fr.janalyse.sotohp.processor.model

import fr.janalyse.sotohp.model.*

import java.nio.file.Path

case class Normalized(
  dimension: Dimension,
  path: Path
)

case class OriginalNormalized(
  original: Original,
  status: ProcessedStatus,
  normalized: Option[Normalized]
) extends ProcessorResult
