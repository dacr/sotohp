package fr.janalyse.sotohp.model

import java.time.OffsetDateTime

case class PhotoHash(
  code: String
) extends AnyVal

case class PhotoKeyword(
  text: String
) extends AnyVal

case class PhotoCategory(
  text: String
) extends AnyVal


case class PhotoKeywords(
  keywords: List[PhotoKeyword]
)

case class Photo(
  timestamp: OffsetDateTime,              // Reference timestamp of the photo, either the shoot date time or the last modified date if the first is unknown
  source: PhotoSource,                    // All information about the original photo file
  metaData: Option[PhotoMetaData],        // Dimension, exif, camera, ... meta data
  category: Option[PhotoCategory] = None, // from user directory tree where photo are store
  place: Option[PhotoPlace] = None,       // where it has been taken

  miniatures: Option[Miniatures] = None,      // all computed and available miniatures
  normalized: Option[NormalizedPhoto] = None, // cleaned, recompressed, resized, optimized photo for quick display and processing

  description: Option[PhotoDescription] = None, // given user description

  foundClassifications: Option[PhotoClassifications] = None,
  foundObjects: Option[PhotoObjects] = None,
  foundFaces: Option[PhotoFaces] = None
)
