package fr.janalyse.sotohp.model

import java.time.OffsetDateTime
import java.util.UUID

case class PhotoOwnerId(
  uuid: UUID
) extends AnyVal

case class PhotoId(
  uuid: UUID
) extends AnyVal

case class PhotoHash(
  code: String
) extends AnyVal

case class PhotoKeyword(
  text: String
) extends AnyVal

case class PhotoCategory(
  text: String
) extends AnyVal

case class DetectedClassification(
  name: String
)

case class DetectedObject(
  name: String,
  box: BoundingBox
)

case class DetectedFace(
  someoneId: Option[SomeoneId],
  box: BoundingBox
)

enum PhotoSource {
  case PhotoFile(
    path: String,
    size: Long,
    hash: PhotoHash,
    lastModified: OffsetDateTime
  )
}

case class PhotoOrientation(
  code: Int
)

case class PhotoMetaData(
  dimension: Dimension2D,
  shootDateTime: Option[OffsetDateTime],
  orientation: Option[PhotoOrientation],
  cameraName: Option[String],
  tags: Map[String, String],
  lastUpdated: OffsetDateTime
)

case class PhotoKeywords(
  keywords: List[PhotoKeyword],
  lastUpdated: OffsetDateTime
)

case class PhotoClassifications(
  classifications: List[DetectedClassification],
  lastUpdated: OffsetDateTime
)

case class PhotoObjects(
  objects: List[DetectedObject],
  lastUpdated: OffsetDateTime
)

case class PhotoFaces(
  faces: List[DetectedFace],
  lastUpdated: OffsetDateTime
)

enum MiniatureSource {
  case MiniatureFile(
    path: String,
    dimension: Dimension2D
  )
}

case class Miniatures(
  sources: List[MiniatureSource],
  lastUpdated: OffsetDateTime
)

case class Photo(
  id: PhotoId,
  ownerId: PhotoOwnerId,
  timestamp: OffsetDateTime,
  source: PhotoSource,
  miniatures: Option[Miniatures],
  metaData: Option[PhotoMetaData],
  foundPlace: Option[GeoPoint],
  foundCategory: Option[PhotoCategory],
  foundKeywords: Option[PhotoKeywords],
  foundClassifications: Option[PhotoClassifications],
  foundObjects: Option[PhotoObjects],
  foundFaces: Option[PhotoFaces]
)
