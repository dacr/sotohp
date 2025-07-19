package fr.janalyse.sotohp.service

import zio.*
import zio.stream.*
import fr.janalyse.sotohp.media.model.*
import zio.lmdb.LMDB

import java.time.OffsetDateTime

trait MediaService {

  // -------------------------------------------------------------------------------------------------------------------
  def mediaFind(nearKey: MediaAccessKey, ownerId: Option[OwnerId]): IO[ServiceIssue, Option[Media]]
  def mediaSearch(keywordsFilter: Set[Keyword], ownerId: Option[OwnerId]): IO[ServiceIssue, Stream[ServiceStreamIssue, Media]]
  def mediaFirst(ownerId: Option[OwnerId]): IO[ServiceIssue, Option[Media]]
  def mediaPrevious(nearKey: MediaAccessKey, ownerId: Option[OwnerId]): IO[ServiceIssue, Option[Media]]
  def mediaNext(nearKey: MediaAccessKey, ownerId: Option[OwnerId]): IO[ServiceIssue, Option[Media]]
  def mediaLast(ownerId: Option[OwnerId]): IO[ServiceIssue, Option[Media]]
  def mediaGet(key: MediaAccessKey, ownerId: Option[OwnerId]): IO[ServiceIssue, Media]
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
  // TODO improve event management
  def eventList(): IO[ServiceIssue, Stream[ServiceStreamIssue, Event]]
  def eventGet(eventId: EventId): IO[ServiceIssue, Event]
  def eventDelete(eventId: EventId): IO[ServiceIssue, Unit]
  def eventCreate(
    ownerId: OwnerId,
    mediaRelativeDirectory: EventMediaDirectory,
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
    providedOwnerId: Option[OwnerId], // If not provided, it will be chosen automatically
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
  def storageList(): IO[ServiceIssue, List[Store]]
  def storageGet(storageId: StoreId): IO[ServiceIssue, Store]
  def storageDelete(storageId: StoreId): IO[ServiceIssue, Unit]
  def storageCreate(
    providedStorageId: Option[StoreId], // If not provided, it will be chosen automatically
    ownerId: OwnerId,
    baseDirectory: BaseDirectoryPath,
    includeMask: Option[IncludeMask],
    ignoreMask: Option[IgnoreMask]
  ): IO[ServiceIssue, Store]
  def storageUpdate(
    storageId: StoreId,
    includeMask: Option[IncludeMask],
    ignoreMask: Option[IgnoreMask]
  ): IO[ServiceIssue, Store]

  // -------------------------------------------------------------------------------------------------------------------
  def synchronize(): IO[ServiceIssue, Unit]
}

object MediaService {
  val live: ZLayer[LMDB, LMDBIssues, MediaService] = ZLayer.fromZIO(
    for {
      lmdb             <- ZIO.service[LMDB]
      mediaServiceLive <- MediaServiceLive.setup(lmdb)
    } yield mediaServiceLive
  )

}
