package fr.janalyse.sotohp.model

import java.time.OffsetDateTime

case class PhotoDescription(
  text: String,
  keywords: Set[String],
  lastUpdated: OffsetDateTime
)
