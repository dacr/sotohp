package fr.janalyse.sotohp.model

import java.time.OffsetDateTime

case class PhotoEvent(
  text: String
) extends AnyVal

case class PhotoKeyword(
  text: String
) extends AnyVal

case class PhotoDescription(
  text: Option[String] = None,               // user description text if some has been given
  event: Option[PhotoEvent] = None,          // default value is based on user directory tree where photo are stored
  keywords: Option[Set[PhotoKeyword]] = None // default value is based on keywords extracted from event using various extraction rules
)
