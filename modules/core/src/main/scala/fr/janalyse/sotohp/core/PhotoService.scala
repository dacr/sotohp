package fr.janalyse.sotohp.core

import zio.*
import zio.stream.*
import fr.janalyse.sotohp.model.*

import java.time.OffsetDateTime

case class PhotoServiceUserIssue(message: String)
case class PhotoServiceSystemIssue(message: String)
type PhotoServiceIssue = PhotoServiceUserIssue | PhotoServiceSystemIssue

case class TimeRange(
  start: Option[OffsetDateTime],
  end: Option[OffsetDateTime]
)

case class PhotoSimpleQuery(
  ownerId: Option[PhotoOwnerId],
  keywords: Option[List[String]],
  timeRange: Option[TimeRange]
)

case class CategorySimpleQuery(
  ownerId: Option[PhotoOwnerId],
  keywords: Option[List[String]]
)

trait PhotoService {
  def photoGet(id: PhotoId): IO[PhotoServiceIssue, Photo]
  def photoFind(query: PhotoSimpleQuery): Stream[PhotoServiceIssue, Photo]
  def categoryFind(query: CategorySimpleQuery): Stream[PhotoServiceIssue, PhotoCategory]
}
