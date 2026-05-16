package fr.janalyse.sotohp.processor.model

import fr.janalyse.sotohp.model.{Original, Something}

case class OriginalDetectedObjects(
  original: Original,
  status: ProcessedStatus,
  objects: List[Something]
) extends ProcessorResult
