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
  def mediaNormalizedRead(key: MediaAccessKey): IO[ServiceIssue, Stream[ServiceStreamIssue, Byte]]
  def mediaOriginalRead(key: MediaAccessKey): IO[ServiceIssue, Stream[ServiceStreamIssue, Byte]]
  def mediaMiniatureRead(key: MediaAccessKey): IO[ServiceIssue, Stream[ServiceStreamIssue, Byte]]

  // -------------------------------------------------------------------------------------------------------------------
  def originalList(): IO[ServiceIssue, Stream[ServiceStreamIssue, Original]]
  def originalGet(originalId: OriginalId): IO[ServiceIssue, Option[Original]]
  def originalDelete(originalId: OriginalId): IO[ServiceIssue, Unit]
  def originalCreate(
    store: Store,
    mediaPath: OriginalPath,
    fileHash: FileHash,
    fileSize: FileSize,
    fileLastModified: FileLastModified,
    cameraShootDateTime: Option[ShootDateTime],
    cameraName: Option[CameraName],
    artistInfo: Option[ArtistInfo],
    dimension: Option[Dimension],
    orientation: Option[Orientation],
    location: Option[Location],
    aperture: Option[Aperture],
    exposureTime: Option[ExposureTime],
    iso: Option[ISO],
    focalLength: Option[FocalLength]
  ): IO[ServiceIssue, Original]
  def originalUpdate(originalId: OriginalId, fileLastModified: FileLastModified): IO[ServiceIssue, Option[Original]]

  // -------------------------------------------------------------------------------------------------------------------
  def eventList(): IO[ServiceIssue, Stream[ServiceStreamIssue, Event]]
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
  def ownerList(): IO[ServiceIssue, List[Owner]]
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
  def storeList(): IO[ServiceIssue, List[Store]]
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

  def mediaFind(nearKey: MediaAccessKey, ownerId: Option[OwnerId]): ZIO[MediaService, ServiceIssue, Option[Media]] = ZIO.serviceWithZIO(_.mediaFind(nearKey, ownerId))

  def mediaSearch(keywordsFilter: Set[Keyword], ownerId: Option[OwnerId]): ZIO[MediaService, ServiceIssue, Stream[ServiceStreamIssue, Media]] = ZIO.serviceWithZIO(_.mediaSearch(keywordsFilter, ownerId))

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
  def originalList(): ZIO[MediaService, ServiceIssue, Stream[ServiceStreamIssue, Original]]                                         = ZIO.serviceWithZIO(_.originalList())
  def originalGet(originalId: OriginalId): ZIO[MediaService, ServiceIssue, Option[Original]]                                        = ZIO.serviceWithZIO(_.originalGet(originalId))
  def originalDelete(originalId: OriginalId): ZIO[MediaService, ServiceIssue, Unit]                                                 = ZIO.serviceWithZIO(_.originalDelete(originalId))
  def originalCreate(
    store: Store,
    mediaPath: OriginalPath,
    fileHash: FileHash,
    fileSize: FileSize,
    fileLastModified: FileLastModified,
    cameraShootDateTime: Option[ShootDateTime],
    cameraName: Option[CameraName],
    artistInfo: Option[ArtistInfo],
    dimension: Option[Dimension],
    orientation: Option[Orientation],
    location: Option[Location],
    aperture: Option[Aperture],
    exposureTime: Option[ExposureTime],
    iso: Option[ISO],
    focalLength: Option[FocalLength]
  ): ZIO[MediaService, ServiceIssue, Original] = ZIO.serviceWithZIO(
    _.originalCreate(
      store,
      mediaPath,
      fileHash,
      fileSize,
      fileLastModified,
      cameraShootDateTime,
      cameraName,
      artistInfo,
      dimension,
      orientation,
      location,
      aperture,
      exposureTime,
      iso,
      focalLength
    )
  )
  def originalUpdate(originalId: OriginalId, fileLastModified: FileLastModified): ZIO[MediaService, ServiceIssue, Option[Original]] = ZIO.serviceWithZIO(_.originalUpdate(originalId, fileLastModified))

  // -------------------------------------------------------------------------------------------------------------------

  def mediaNormalizedRead(key: MediaAccessKey): ZIO[MediaService, ServiceIssue, Stream[ServiceStreamIssue, Byte]] = ZIO.serviceWithZIO(_.mediaNormalizedRead(key))

  def mediaOriginalRead(key: MediaAccessKey): ZIO[MediaService, ServiceIssue, Stream[ServiceStreamIssue, Byte]] = ZIO.serviceWithZIO(_.mediaOriginalRead(key))

  def mediaMiniatureRead(key: MediaAccessKey): ZIO[MediaService, ServiceIssue, Stream[ServiceStreamIssue, Byte]] = ZIO.serviceWithZIO(_.mediaMiniatureRead(key))

  // -------------------------------------------------------------------------------------------------------------------

  def eventList(): ZIO[MediaService, ServiceIssue, Stream[ServiceStreamIssue, Event]] = ZIO.serviceWithZIO(_.eventList())

  def eventGet(eventId: EventId): ZIO[MediaService, ServiceIssue, Option[Event]] = ZIO.serviceWithZIO(_.eventGet(eventId))

  def eventDelete(eventId: EventId): ZIO[MediaService, ServiceIssue, Unit] = ZIO.serviceWithZIO(_.eventDelete(eventId))

  def eventCreate(attachment: Option[EventAttachment], name: EventName, description: Option[EventDescription], keywords: Set[Keyword]): ZIO[MediaService, ServiceIssue, Event] =
    ZIO.serviceWithZIO(_.eventCreate(attachment, name, description, keywords))

  def eventUpdate(eventId: EventId, attachment: Option[EventAttachment], name: EventName, description: Option[EventDescription], keywords: Set[Keyword]): ZIO[MediaService, ServiceIssue, Option[Event]] =
    ZIO.serviceWithZIO(_.eventUpdate(eventId, attachment, name, description, keywords))

  // -------------------------------------------------------------------------------------------------------------------

  def ownerList(): ZIO[MediaService, ServiceIssue, List[Owner]] = ZIO.serviceWithZIO(_.ownerList())

  def ownerGet(ownerId: OwnerId): ZIO[MediaService, ServiceIssue, Option[Owner]] = ZIO.serviceWithZIO(_.ownerGet(ownerId))

  def ownerDelete(ownerId: OwnerId): ZIO[MediaService, ServiceIssue, Unit] = ZIO.serviceWithZIO(_.ownerDelete(ownerId))

  def ownerCreate(providedOwnerId: Option[OwnerId], firstName: FirstName, lastName: LastName, birthDate: Option[BirthDate]): ZIO[MediaService, ServiceIssue, Owner] = ZIO.serviceWithZIO(_.ownerCreate(providedOwnerId, firstName, lastName, birthDate))

  def ownerUpdate(ownerId: OwnerId, firstName: FirstName, lastName: LastName, birthDate: Option[BirthDate]): ZIO[MediaService, ServiceIssue, Option[Owner]] = ZIO.serviceWithZIO(_.ownerUpdate(ownerId, firstName, lastName, birthDate))

  // -------------------------------------------------------------------------------------------------------------------

  def storeList(): ZIO[MediaService, ServiceIssue, List[Store]] = ZIO.serviceWithZIO(_.storeList())

  def storeGet(storeId: StoreId): ZIO[MediaService, ServiceIssue, Option[Store]] = ZIO.serviceWithZIO(_.storeGet(storeId))

  def storeDelete(storeId: StoreId): ZIO[MediaService, ServiceIssue, Unit] = ZIO.serviceWithZIO(_.storeDelete(storeId))

  def storeCreate(providedStoreId: Option[StoreId], ownerId: OwnerId, baseDirectory: BaseDirectoryPath, includeMask: Option[IncludeMask], ignoreMask: Option[IgnoreMask]): ZIO[MediaService, ServiceIssue, Store] =
    ZIO.serviceWithZIO(_.storeCreate(providedStoreId, ownerId, baseDirectory, includeMask, ignoreMask))

  def storeUpdate(storeId: StoreId, includeMask: Option[IncludeMask], ignoreMask: Option[IgnoreMask]): ZIO[MediaService, ServiceIssue, Option[Store]] = ZIO.serviceWithZIO(_.storeUpdate(storeId, includeMask, ignoreMask))

  // -------------------------------------------------------------------------------------------------------------------

  def synchronize(): ZIO[MediaService, ServiceIssue, Unit] = ZIO.serviceWithZIO(_.synchronize())
}
