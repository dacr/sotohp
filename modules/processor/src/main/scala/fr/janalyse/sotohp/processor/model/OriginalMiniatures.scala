package fr.janalyse.sotohp.processor.model

import fr.janalyse.sotohp.model.{Dimension, Original}

case class OriginalMiniature(
  size: Int,
  dimension: Dimension
)

case class OriginalMiniatures(
  original: Original,
  status: ProcessedStatus,
  miniatures: Map[Int, OriginalMiniature]
) extends ProcessorResult
