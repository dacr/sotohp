package fr.janalyse.sotohp.service

import fr.janalyse.sotohp.media.model.*
import fr.janalyse.sotohp.service.dao.*
import wvlet.airframe.ulid.ULID
import zio.*
import zio.lmdb.{LMDB, LMDBCodec, LMDBCollection, LMDBKodec, StorageSystemError, StorageUserError}
import zio.stream.Stream

import java.nio.ByteBuffer
import java.util.UUID
import scala.util.{Failure, Success, Try}

type LMDBIssues = StorageUserError | StorageSystemError

class MediaServiceLive private (
  lmdb: LMDB,
  originals: LMDBCollection[OriginalId, DaoOriginal],
  states: LMDBCollection[OriginalId, DaoState],
  events: LMDBCollection[EventId, DaoEvent],
  medias: LMDBCollection[MediaAccessKey, DaoMedia],
  owners: LMDBCollection[OwnerId, DaoOwner],
  stores: LMDBCollection[StoreId, DaoStore]
) extends MediaService {

  // -------------------------------------------------------------------------------------------------------------------

  override def mediaFind(nearKey: MediaAccessKey, ownerId: Option[OwnerId]): IO[ServiceIssue, Option[Media]] = ???

  override def mediaSearch(keywordsFilter: Set[Keyword], ownerId: Option[OwnerId]): IO[ServiceIssue, stream.Stream[ServiceStreamIssue, Media]] = ???

  override def mediaFirst(ownerId: Option[OwnerId]): IO[ServiceIssue, Option[Media]] = ???

  override def mediaPrevious(nearKey: MediaAccessKey, ownerId: Option[OwnerId]): IO[ServiceIssue, Option[Media]] = ???

  override def mediaNext(nearKey: MediaAccessKey, ownerId: Option[OwnerId]): IO[ServiceIssue, Option[Media]] = ???

  override def mediaLast(ownerId: Option[OwnerId]): IO[ServiceIssue, Option[Media]] = ???

  override def mediaGet(key: MediaAccessKey, ownerId: Option[OwnerId]): IO[ServiceIssue, Media] = ???

  override def mediaUpdate(
    key: MediaAccessKey,
    eventId: Option[EventId],
    description: Option[MediaDescription],
    starred: Starred,
    keywords: Set[Keyword],
    orientation: Option[Orientation],
    shootDateTime: Option[ShootDateTime],
    location: Option[Location]
  ): IO[ServiceIssue, Media] = ???

  override def mediaNormalizedRead(key: MediaAccessKey): IO[ServiceIssue, stream.Stream[ServiceStreamIssue, Byte]] = ???

  override def mediaOriginalRead(key: MediaAccessKey): IO[ServiceIssue, stream.Stream[ServiceStreamIssue, Byte]] = ???

  override def mediaMiniatureRead(key: MediaAccessKey): IO[ServiceIssue, stream.Stream[ServiceStreamIssue, Byte]] = ???

  // -------------------------------------------------------------------------------------------------------------------

  override def eventList(): IO[ServiceIssue, stream.Stream[ServiceStreamIssue, Event]] = ???

  override def eventGet(eventId: EventId): IO[ServiceIssue, Event] = ???

  override def eventDelete(eventId: EventId): IO[ServiceIssue, Unit] = ???

  override def eventCreate(ownerId: OwnerId, mediaRelativeDirectory: EventMediaDirectory, name: EventName, description: Option[EventDescription], keywords: Set[Keyword]): IO[ServiceIssue, Event] = ???

  override def eventUpdate(eventId: EventId, name: EventName, description: Option[EventDescription], keywords: Set[Keyword]): IO[ServiceIssue, Event] = ???

  // -------------------------------------------------------------------------------------------------------------------

  override def ownerList(): IO[ServiceIssue, List[Owner]] = ???

  override def ownerGet(ownerId: OwnerId): IO[ServiceIssue, Owner] = ???

  override def ownerDelete(ownerId: OwnerId): IO[ServiceIssue, Unit] = ???

  override def ownerCreate(providedOwnerId: Option[OwnerId], firstName: FirstName, lastName: LastName, birthDate: Option[BirthDate]): IO[ServiceIssue, Owner] = ???

  override def ownerUpdate(ownerId: OwnerId, firstName: FirstName, lastName: LastName, birthDate: Option[BirthDate]): IO[ServiceIssue, Owner] = ???

  // -------------------------------------------------------------------------------------------------------------------

  override def storageList(): IO[ServiceIssue, List[Store]] = ???

  override def storageGet(storageId: StoreId): IO[ServiceIssue, Store] = ???

  override def storageDelete(storageId: StoreId): IO[ServiceIssue, Unit] = ???

  override def storageCreate(providedStorageId: Option[StoreId], ownerId: OwnerId, baseDirectory: BaseDirectoryPath, includeMask: Option[IncludeMask], ignoreMask: Option[IgnoreMask]): IO[ServiceIssue, Store] = ???

  override def storageUpdate(storageId: StoreId, includeMask: Option[IncludeMask], ignoreMask: Option[IgnoreMask]): IO[ServiceIssue, Store] = ???

  // -------------------------------------------------------------------------------------------------------------------

  override def synchronize(): IO[ServiceIssue, Unit] = {
    ???
  }
}

object MediaServiceLive {

  // -------------------------------------------------------------------------------------------------------------------
  private def uuidBytesToEither(uuidBytes: ByteBuffer): Either[String, UUID] = Try {
    UUID.fromString(new String(uuidBytes.array(), "UTF-8"))
  } match {
    case Failure(exception) => Left(exception.getMessage)
    case Success(uuid)      => Right(uuid)
  }

  // -------------------------------------------------------------------------------------------------------------------
  private def ulidBytesToEither(ulidBytes: ByteBuffer): Either[String, ULID] = Try {
    ULID.fromString(new String(ulidBytes.array(), "UTF-8"))
  } match {
    case Failure(exception) => Left(exception.getMessage)
    case Success(ulid)      => Right(ulid)
  }

  // -------------------------------------------------------------------------------------------------------------------
  given LMDBKodec[OriginalId] = new LMDBKodec {
    def encode(key: OriginalId): Array[Byte]                     = key.asString.getBytes()
    def decode(keyBytes: ByteBuffer): Either[String, OriginalId] = uuidBytesToEither(keyBytes).map(OriginalId.apply)
  }

  // -------------------------------------------------------------------------------------------------------------------
  given LMDBKodec[EventId] = new LMDBKodec {
    def encode(key: EventId): Array[Byte]                     = key.asString.getBytes()
    def decode(keyBytes: ByteBuffer): Either[String, EventId] = uuidBytesToEither(keyBytes).map(EventId.apply)
  }

  // -------------------------------------------------------------------------------------------------------------------
  given LMDBKodec[MediaAccessKey] = new LMDBKodec {
    def encode(key: MediaAccessKey): Array[Byte]                     = key.asString.getBytes()
    def decode(keyBytes: ByteBuffer): Either[String, MediaAccessKey] = ulidBytesToEither(keyBytes).map(MediaAccessKey.apply)
  }

  // -------------------------------------------------------------------------------------------------------------------
  given LMDBKodec[OwnerId] = new LMDBKodec {
    def encode(key: OwnerId): Array[Byte]                     = key.asString.getBytes()
    def decode(keyBytes: ByteBuffer): Either[String, OwnerId] = ulidBytesToEither(keyBytes).map(OwnerId.apply)
  }

  // -------------------------------------------------------------------------------------------------------------------
  given LMDBKodec[StoreId] = new LMDBKodec {
    def encode(key: StoreId): Array[Byte]                     = key.asString.getBytes()
    def decode(keyBytes: ByteBuffer): Either[String, StoreId] = uuidBytesToEither(keyBytes).map(StoreId.apply)
  }

  // -------------------------------------------------------------------------------------------------------------------
  given LMDBCodec[MediaAccessKey] = new LMDBCodec {
    def encode(key: MediaAccessKey): Array[Byte]                     = key.asString.getBytes()
    def decode(keyBytes: ByteBuffer): Either[String, MediaAccessKey] = ulidBytesToEither(keyBytes).map(MediaAccessKey.apply)
  }

  // -------------------------------------------------------------------------------------------------------------------
  private val originalsCollectionName = "originals"
  private val statesCollectionName    = "links"
  private val eventsCollectionName    = "events"
  private val mediasCollectionName    = "medias"
  private val ownersCollectionName    = "owners"
  private val storesCollectionName    = "stores"
  private val allCollections          = List(originalsCollectionName, statesCollectionName, eventsCollectionName, mediasCollectionName, ownersCollectionName, storesCollectionName)

  def setup(lmdb: LMDB): IO[LMDBIssues, MediaService] = for {
    _             <- ZIO.foreachDiscard(allCollections)(col => lmdb.collectionAllocate(col).ignore)
    originalsColl <- lmdb.collectionGet[OriginalId, DaoOriginal](originalsCollectionName)
    statesColl    <- lmdb.collectionGet[OriginalId, DaoState](statesCollectionName)
    eventsColl    <- lmdb.collectionGet[EventId, DaoEvent](eventsCollectionName)
    mediasColl    <- lmdb.collectionGet[MediaAccessKey, DaoMedia](mediasCollectionName)
    ownersColl    <- lmdb.collectionGet[OwnerId, DaoOwner](ownersCollectionName)
    storesColl    <- lmdb.collectionGet[StoreId, DaoStore](storesCollectionName)
  } yield new MediaServiceLive(lmdb, originalsColl, statesColl, eventsColl, mediasColl, ownersColl, storesColl)

  // -------------------------------------------------------------------------------------------------------------------

}
