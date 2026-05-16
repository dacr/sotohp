package fr.janalyse.sotohp.processor.model

import fr.janalyse.sotohp.model.{MiniatureCharacteristics, Original}

case class OriginalMiniatures(
  original: Original,
  status: ProcessedStatus,
  miniatures: Map[Int, MiniatureCharacteristics]
) extends ProcessorResult
