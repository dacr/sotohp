import zio.*
import zio.stream.*
import fr.janalyse.sotohp.model.*

import java.time.OffsetDateTime

case class PhotoServiceUserIssue(message: String)
case class PhotoServiceSystemIssue(message: String)

type PhotoServiceIssue = PhotoServiceUserIssue | PhotoServiceSystemIssue
type PhotoServiceStreamIssue = PhotoServiceSystemIssue

case class TimeRange(
  start: Option[OffsetDateTime],
  end: Option[OffsetDateTime]
)

case class PhotoQuery(
  ownerId: Option[PhotoOwnerId],
  keywords: PhotoKeywords,
  timeRange: Option[TimeRange]
)

case class EventQuery(
  ownerId: Option[PhotoOwnerId],
  keywords: PhotoKeywords,
  timeRange: Option[TimeRange]
)

case class PhotoEventInfo(
  event: PhotoEvent,
  timeRange: Option[TimeRange]
)

trait PhotoService {
  def photoGet(id: PhotoId): IO[PhotoServiceIssue, Photo]
  def photoFind(query: PhotoQuery): IO[PhotoServiceStreamIssue, Stream[PhotoServiceIssue, Photo]]
  def eventFind(query: EventQuery): IO[PhotoServiceStreamIssue, Stream[PhotoServiceIssue, PhotoEventInfo]]
}
