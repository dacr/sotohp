package fr.janalyse.sotohp.processor.model

import java.time.OffsetDateTime

case class ProcessedStatus(
  successful: Boolean,
  timestamp: OffsetDateTime
)

trait ProcessorResult {
  val status: ProcessedStatus
}
