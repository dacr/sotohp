package fr.janalyse.sotohp.model

import java.time.OffsetDateTime

case class PhotoCategory(
  text: String
) extends AnyVal

case class PhotoKeyword(
  text: String
) extends AnyVal

case class PhotoDescription(
  text: Option[String] = None,               // user description text if some has been given
  category: Option[PhotoCategory] = None,    // default value is based on user directory tree where photo are stored
  keywords: Option[Set[PhotoKeyword]] = None // default value is based on keywords extracted from category using various extraction rules
)
