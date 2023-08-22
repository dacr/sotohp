package fr.janalyse.sotohp.model

import java.time.OffsetDateTime

case class PhotoDescription(
  text: Option[String],
  keywords: Set[String]
)
