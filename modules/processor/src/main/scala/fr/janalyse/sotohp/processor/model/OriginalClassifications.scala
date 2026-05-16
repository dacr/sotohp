package fr.janalyse.sotohp.processor.model

import fr.janalyse.sotohp.model.{Classification, Original}

case class OriginalClassifications(
  original: Original,
  status: ProcessedStatus,
  classifications: List[Classification]
) extends ProcessorResult
