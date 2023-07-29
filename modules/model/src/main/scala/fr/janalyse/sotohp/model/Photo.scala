package fr.janalyse.sotohp.model

import java.time.{Instant, OffsetDateTime}
import java.util.UUID

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

enum PhotoOrientation(val code: Int, val description: String) {
  case Horizontal                            extends PhotoOrientation(1, "Horizontal (normal)")
  case MirrorHorizontal                      extends PhotoOrientation(2, "Mirror horizontal")
  case Rotate180                             extends PhotoOrientation(3, "Rotate 180")
  case MirrorVertical                        extends PhotoOrientation(4, "Mirror vertical")
  case MirrorHorizontalAndRotate270ClockWise extends PhotoOrientation(5, "Mirror horizontal and rotate 270 CW")
  case Rotate90ClockWise                     extends PhotoOrientation(6, "Rotate 90 CW")
  case MirrorHorizontalAndRotate90ClockWise  extends PhotoOrientation(7, "Mirror horizontal and rotate 90 CW")
  case Rotate270ClockWise                    extends PhotoOrientation(8, "Rotate 270 CW")
}

case class PhotoMetaData(
  dimension: Option[Dimension2D],
  shootDateTime: Option[OffsetDateTime],
  orientation: Option[PhotoOrientation],
  cameraName: Option[String],
  tags: Map[String, String]
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
  timestamp: OffsetDateTime,
  source: PhotoSource,
  metaData: Option[PhotoMetaData],
  category: Option[PhotoCategory] = None,
  place: Option[GeoPoint] = None,
  miniatures: Option[Miniatures] = None,
  foundKeywords: Option[PhotoKeywords] = None,
  foundClassifications: Option[PhotoClassifications] = None,
  foundObjects: Option[PhotoObjects] = None,
  foundFaces: Option[PhotoFaces] = None
)
