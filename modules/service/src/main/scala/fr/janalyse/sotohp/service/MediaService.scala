package fr.janalyse.sotohp.service

import zio.*
import zio.stream.*
import fr.janalyse.sotohp.media.model.*

import java.time.OffsetDateTime

case class TimeRange(
  start: Option[OffsetDateTime],
  end: Option[OffsetDateTime]
)

case class MediaQuery(
  ownerId: Option[OwnerId],
  keywords: Set[Keyword],
  timeRange: Option[TimeRange]
)

case class EventQuery(
  ownerId: Option[OwnerId],
  keywords: Set[Keyword],
  timeRange: Option[TimeRange]
)

trait MediaService {

  def mediaGet(key: MediaAccessKey): IO[ServiceIssue, Media]
  def mediaFind(nearKey: MediaAccessKey): IO[ServiceIssue, Media]
  def mediaSearch(query: MediaQuery): IO[ServiceIssue, Stream[ServiceStreamIssue, Media]]

  def mediaNormalizedRead(key: MediaAccessKey): IO[ServiceIssue, Stream[ServiceStreamIssue, Byte]]
  def mediaOriginalRead(key: MediaAccessKey): IO[ServiceIssue, Stream[ServiceStreamIssue, Byte]]
  def mediaMiniatureRead(key: MediaAccessKey): IO[ServiceIssue, Stream[ServiceStreamIssue, Byte]]

  def eventSearch(query: EventQuery): IO[ServiceIssue, Stream[ServiceStreamIssue, Event]]

  def storageCreate(ownerId: OwnerId, baseDirectory: BaseDirectoryPath, includeMask: Option[IncludeMask], ignoreMask: Option[IgnoreMask]): IO[ServiceIssue, Storage]
  def storageList(): IO[ServiceIssue, List[Storage]]
  def storageGet(storageId: StorageId): IO[ServiceIssue, Storage]
  def storageUpdate(storageId: StorageId, includeMask: Option[IncludeMask], ignoreMask: Option[IgnoreMask]): IO[ServiceIssue, Unit]
  def storageDelete(storageId: StorageId): IO[ServiceIssue, Unit]

  def synchronize(): IO[ServiceIssue, Unit]
}
