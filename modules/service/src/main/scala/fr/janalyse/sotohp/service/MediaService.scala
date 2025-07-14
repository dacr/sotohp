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

trait MediaService {

  // -------------------------------------------------------------------------------------------------------------------
  def mediaFind(nearKey: MediaAccessKey): IO[ServiceIssue, Option[Media]]
  def mediaSearch(query: MediaQuery): IO[ServiceIssue, Stream[ServiceStreamIssue, Media]]
  def mediaGet(key: MediaAccessKey): IO[ServiceIssue, Media]
  def mediaUpdate(
    key: MediaAccessKey,
    eventId: Option[EventId],
    description: Option[MediaDescription],
    starred: Starred,
    keywords: Set[Keyword],
    orientation: Option[Orientation],
    shootDateTime: Option[ShootDateTime],
    location: Option[Location]
  ): IO[ServiceIssue, Media]

  // -------------------------------------------------------------------------------------------------------------------
  def mediaNormalizedRead(key: MediaAccessKey): IO[ServiceIssue, Stream[ServiceStreamIssue, Byte]]
  def mediaOriginalRead(key: MediaAccessKey): IO[ServiceIssue, Stream[ServiceStreamIssue, Byte]]
  def mediaMiniatureRead(key: MediaAccessKey): IO[ServiceIssue, Stream[ServiceStreamIssue, Byte]]

  // -------------------------------------------------------------------------------------------------------------------
  def eventList(): IO[ServiceIssue, Stream[ServiceStreamIssue, Event]]
  def eventGet(eventId: EventId): IO[ServiceIssue, Event]
  def eventDelete(eventId: EventId): IO[ServiceIssue, Unit]
  def eventCreate(
    ownerId: OwnerId,
    mediaDirectory: EventMediaDirectory,
    name: EventName,
    description: Option[EventDescription],
    keywords: Set[Keyword]
  ): IO[ServiceIssue, Event]
  def eventUpdate(
    eventId: EventId,
    name: EventName,
    description: Option[EventDescription],
    keywords: Set[Keyword]
  ): IO[ServiceIssue, Event]

  // -------------------------------------------------------------------------------------------------------------------
  def ownerList(): IO[ServiceIssue, List[Owner]]
  def ownerGet(ownerId: OwnerId): IO[ServiceIssue, Owner]
  def ownerDelete(ownerId: OwnerId): IO[ServiceIssue, Unit]
  def ownerCreate(
    firstName: FirstName,
    lastName: LastName,
    birthDate: Option[BirthDate]
  ): IO[ServiceIssue, Owner]
  def ownerUpdate(
    ownerId: OwnerId,
    firstName: FirstName,
    lastName: LastName,
    birthDate: Option[BirthDate]
  ): IO[ServiceIssue, Owner]

  // -------------------------------------------------------------------------------------------------------------------
  def storageList(): IO[ServiceIssue, List[Storage]]
  def storageGet(storageId: StorageId): IO[ServiceIssue, Storage]
  def storageDelete(storageId: StorageId): IO[ServiceIssue, Unit]
  def storageCreate(
    ownerId: OwnerId,
    baseDirectory: BaseDirectoryPath,
    includeMask: Option[IncludeMask],
    ignoreMask: Option[IgnoreMask]
  ): IO[ServiceIssue, Storage]
  def storageUpdate(
    storageId: StorageId,
    includeMask: Option[IncludeMask],
    ignoreMask: Option[IgnoreMask]
  ): IO[ServiceIssue, Storage]

  // -------------------------------------------------------------------------------------------------------------------
  def synchronize(): IO[ServiceIssue, Unit]
}
