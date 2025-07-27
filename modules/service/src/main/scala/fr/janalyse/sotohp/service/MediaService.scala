package fr.janalyse.sotohp.service

import zio.*
import zio.stream.*
import fr.janalyse.sotohp.media.model.*
import zio.lmdb.LMDB

import java.time.OffsetDateTime

trait MediaService {

  // -------------------------------------------------------------------------------------------------------------------
  def mediaList(): Stream[ServiceStreamIssue, Media]
  def mediaFind(nearKey: MediaAccessKey, ownerId: Option[OwnerId]): IO[ServiceIssue, Option[Media]]
  def mediaSearch(keywordsFilter: Set[Keyword], ownerId: Option[OwnerId]): Stream[ServiceStreamIssue, Media]
  def mediaFirst(ownerId: Option[OwnerId]): IO[ServiceIssue, Option[Media]]
  def mediaPrevious(nearKey: MediaAccessKey, ownerId: Option[OwnerId]): IO[ServiceIssue, Option[Media]]
  def mediaNext(nearKey: MediaAccessKey, ownerId: Option[OwnerId]): IO[ServiceIssue, Option[Media]]
  def mediaLast(ownerId: Option[OwnerId]): IO[ServiceIssue, Option[Media]]
  def mediaGet(key: MediaAccessKey, ownerId: Option[OwnerId]): IO[ServiceIssue, Option[Media]]
  def mediaUpdate(
    key: MediaAccessKey,
    eventId: Option[EventId],
    description: Option[MediaDescription],
    starred: Starred,
    keywords: Set[Keyword],
    orientation: Option[Orientation],
    shootDateTime: Option[ShootDateTime],
    location: Option[Location]
  ): IO[ServiceIssue, Option[Media]]

  // -------------------------------------------------------------------------------------------------------------------
  def mediaNormalizedRead(key: MediaAccessKey): Stream[ServiceStreamIssue, Byte]
  def mediaOriginalRead(key: MediaAccessKey): Stream[ServiceStreamIssue, Byte]
  def mediaMiniatureRead(key: MediaAccessKey): Stream[ServiceStreamIssue, Byte]

  // -------------------------------------------------------------------------------------------------------------------
  def stateList(): Stream[ServiceStreamIssue, State]
  def stateGet(originalId: OriginalId): IO[ServiceIssue, Option[State]]
  def stateDelete(originalId: OriginalId): IO[ServiceIssue, Unit]
  def stateUpsert(originalId: OriginalId, state: State): IO[ServiceIssue, State]

  // -------------------------------------------------------------------------------------------------------------------
  def originalList(): Stream[ServiceStreamIssue, Original]
  def originalGet(originalId: OriginalId): IO[ServiceIssue, Option[Original]]
  def originalExists(originalId: OriginalId): IO[ServiceIssue, Boolean]
  def originalDelete(originalId: OriginalId): IO[ServiceIssue, Unit]
  def originalUpsert(providedOriginal: Original): IO[ServiceIssue, Original]

  // -------------------------------------------------------------------------------------------------------------------
  def eventList(): Stream[ServiceStreamIssue, Event]
  def eventGet(eventId: EventId): IO[ServiceIssue, Option[Event]]
  def eventDelete(eventId: EventId): IO[ServiceIssue, Unit]
  def eventCreate(
    attachment: Option[EventAttachment],
    name: EventName,
    description: Option[EventDescription],
    keywords: Set[Keyword]
  ): IO[ServiceIssue, Event]
  def eventUpdate(
    eventId: EventId,
    attachment: Option[EventAttachment],
    name: EventName,
    description: Option[EventDescription],
    keywords: Set[Keyword]
  ): IO[ServiceIssue, Option[Event]]

  // -------------------------------------------------------------------------------------------------------------------
  def ownerList(): Stream[ServiceIssue, Owner]
  def ownerGet(ownerId: OwnerId): IO[ServiceIssue, Option[Owner]]
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
  ): IO[ServiceIssue, Option[Owner]]

  // -------------------------------------------------------------------------------------------------------------------
  def storeList(): Stream[ServiceIssue, Store]
  def storeGet(storeId: StoreId): IO[ServiceIssue, Option[Store]]
  def storeDelete(storeId: StoreId): IO[ServiceIssue, Unit]
  def storeCreate(
    providedStoreId: Option[StoreId], // If not provided, it will be chosen automatically
    ownerId: OwnerId,
    baseDirectory: BaseDirectoryPath,
    includeMask: Option[IncludeMask],
    ignoreMask: Option[IgnoreMask]
  ): IO[ServiceIssue, Store]
  def storeUpdate(
    storeId: StoreId,
    includeMask: Option[IncludeMask],
    ignoreMask: Option[IgnoreMask]
  ): IO[ServiceIssue, Option[Store]]

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

  // -------------------------------------------------------------------------------------------------------------------

  def mediaList(): ZStream[MediaService, ServiceStreamIssue, Media] = ZStream.serviceWithStream(_.mediaList())

  def mediaFind(nearKey: MediaAccessKey, ownerId: Option[OwnerId]): ZIO[MediaService, ServiceIssue, Option[Media]] = ZIO.serviceWithZIO(_.mediaFind(nearKey, ownerId))

  def mediaSearch(keywordsFilter: Set[Keyword], ownerId: Option[OwnerId]): ZStream[MediaService, ServiceStreamIssue, Media] = ZStream.serviceWithStream(_.mediaSearch(keywordsFilter, ownerId))

  def mediaFirst(ownerId: Option[OwnerId]): ZIO[MediaService, ServiceIssue, Option[Media]] = ZIO.serviceWithZIO(_.mediaFirst(ownerId))

  def mediaPrevious(nearKey: MediaAccessKey, ownerId: Option[OwnerId]): ZIO[MediaService, ServiceIssue, Option[Media]] = ZIO.serviceWithZIO(_.mediaPrevious(nearKey, ownerId))

  def mediaNext(nearKey: MediaAccessKey, ownerId: Option[OwnerId]): ZIO[MediaService, ServiceIssue, Option[Media]] = ZIO.serviceWithZIO(_.mediaNext(nearKey, ownerId))

  def mediaLast(ownerId: Option[OwnerId]): ZIO[MediaService, ServiceIssue, Option[Media]] = ZIO.serviceWithZIO(_.mediaLast(ownerId))

  def mediaGet(key: MediaAccessKey, ownerId: Option[OwnerId]): ZIO[MediaService, ServiceIssue, Option[Media]] = ZIO.serviceWithZIO(_.mediaGet(key, ownerId))

  def mediaUpdate(
    key: MediaAccessKey,
    eventId: Option[EventId],
    description: Option[MediaDescription],
    starred: Starred,
    keywords: Set[Keyword],
    orientation: Option[Orientation],
    shootDateTime: Option[ShootDateTime],
    location: Option[Location]
  ): ZIO[MediaService, ServiceIssue, Option[Media]] =
    ZIO.serviceWithZIO(_.mediaUpdate(key, eventId, description, starred, keywords, orientation, shootDateTime, location))

  // -------------------------------------------------------------------------------------------------------------------
  def stateList(): ZStream[MediaService, ServiceStreamIssue, State]                             = ZStream.serviceWithStream(_.stateList())
  def stateGet(originalId: OriginalId): ZIO[MediaService, ServiceIssue, Option[State]]          = ZIO.serviceWithZIO(_.stateGet(originalId))
  def stateDelete(originalId: OriginalId): ZIO[MediaService, ServiceIssue, Unit]                = ZIO.serviceWithZIO(_.stateDelete(originalId))
  def stateUpsert(originalId: OriginalId, state: State): ZIO[MediaService, ServiceIssue, State] = ZIO.serviceWithZIO(_.stateUpsert(originalId, state))

  // -------------------------------------------------------------------------------------------------------------------
  def originalList(): ZStream[MediaService, ServiceStreamIssue, Original]                    = ZStream.serviceWithStream(_.originalList())
  def originalGet(originalId: OriginalId): ZIO[MediaService, ServiceIssue, Option[Original]] = ZIO.serviceWithZIO(_.originalGet(originalId))
  def originalExists(originalId: OriginalId): ZIO[MediaService, ServiceIssue, Boolean]       = ZIO.serviceWithZIO(_.originalExists(originalId))
  def originalDelete(originalId: OriginalId): ZIO[MediaService, ServiceIssue, Unit]          = ZIO.serviceWithZIO(_.originalDelete(originalId))
  def originalUpsert(providedOriginal: Original): ZIO[MediaService, ServiceIssue, Original]  = ZIO.serviceWithZIO(_.originalUpsert(providedOriginal))

  // -------------------------------------------------------------------------------------------------------------------

  def mediaNormalizedRead(key: MediaAccessKey): ZStream[MediaService, ServiceStreamIssue, Byte] = ZStream.serviceWithStream(_.mediaNormalizedRead(key))

  def mediaOriginalRead(key: MediaAccessKey): ZStream[MediaService, ServiceStreamIssue, Byte] = ZStream.serviceWithStream(_.mediaOriginalRead(key))

  def mediaMiniatureRead(key: MediaAccessKey): ZStream[MediaService, ServiceStreamIssue, Byte] = ZStream.serviceWithStream(_.mediaMiniatureRead(key))

  // -------------------------------------------------------------------------------------------------------------------

  def eventList(): ZStream[MediaService, ServiceStreamIssue, Event] = ZStream.serviceWithStream(_.eventList())

  def eventGet(eventId: EventId): ZIO[MediaService, ServiceIssue, Option[Event]] = ZIO.serviceWithZIO(_.eventGet(eventId))

  def eventDelete(eventId: EventId): ZIO[MediaService, ServiceIssue, Unit] = ZIO.serviceWithZIO(_.eventDelete(eventId))

  def eventCreate(attachment: Option[EventAttachment], name: EventName, description: Option[EventDescription], keywords: Set[Keyword]): ZIO[MediaService, ServiceIssue, Event] =
    ZIO.serviceWithZIO(_.eventCreate(attachment, name, description, keywords))

  def eventUpdate(eventId: EventId, attachment: Option[EventAttachment], name: EventName, description: Option[EventDescription], keywords: Set[Keyword]): ZIO[MediaService, ServiceIssue, Option[Event]] =
    ZIO.serviceWithZIO(_.eventUpdate(eventId, attachment, name, description, keywords))

  // -------------------------------------------------------------------------------------------------------------------

  def ownerList(): ZStream[MediaService, ServiceIssue, Owner] = ZStream.serviceWithStream(_.ownerList())

  def ownerGet(ownerId: OwnerId): ZIO[MediaService, ServiceIssue, Option[Owner]] = ZIO.serviceWithZIO(_.ownerGet(ownerId))

  def ownerDelete(ownerId: OwnerId): ZIO[MediaService, ServiceIssue, Unit] = ZIO.serviceWithZIO(_.ownerDelete(ownerId))

  def ownerCreate(providedOwnerId: Option[OwnerId], firstName: FirstName, lastName: LastName, birthDate: Option[BirthDate]): ZIO[MediaService, ServiceIssue, Owner] = ZIO.serviceWithZIO(_.ownerCreate(providedOwnerId, firstName, lastName, birthDate))

  def ownerUpdate(ownerId: OwnerId, firstName: FirstName, lastName: LastName, birthDate: Option[BirthDate]): ZIO[MediaService, ServiceIssue, Option[Owner]] = ZIO.serviceWithZIO(_.ownerUpdate(ownerId, firstName, lastName, birthDate))

  // -------------------------------------------------------------------------------------------------------------------

  def storeList(): ZStream[MediaService, ServiceIssue, Store] = ZStream.serviceWithStream(_.storeList())

  def storeGet(storeId: StoreId): ZIO[MediaService, ServiceIssue, Option[Store]] = ZIO.serviceWithZIO(_.storeGet(storeId))

  def storeDelete(storeId: StoreId): ZIO[MediaService, ServiceIssue, Unit] = ZIO.serviceWithZIO(_.storeDelete(storeId))

  def storeCreate(providedStoreId: Option[StoreId], ownerId: OwnerId, baseDirectory: BaseDirectoryPath, includeMask: Option[IncludeMask], ignoreMask: Option[IgnoreMask]): ZIO[MediaService, ServiceIssue, Store] =
    ZIO.serviceWithZIO(_.storeCreate(providedStoreId, ownerId, baseDirectory, includeMask, ignoreMask))

  def storeUpdate(storeId: StoreId, includeMask: Option[IncludeMask], ignoreMask: Option[IgnoreMask]): ZIO[MediaService, ServiceIssue, Option[Store]] = ZIO.serviceWithZIO(_.storeUpdate(storeId, includeMask, ignoreMask))

  // -------------------------------------------------------------------------------------------------------------------

  def synchronize(): ZIO[MediaService, ServiceIssue, Unit] = ZIO.serviceWithZIO(_.synchronize())
}
