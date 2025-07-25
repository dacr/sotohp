package fr.janalyse.sotohp.service

import fr.janalyse.sotohp.media.model.*
import fr.janalyse.sotohp.service.dao.*
import wvlet.airframe.ulid.ULID
import zio.*
import zio.lmdb.{LMDB, LMDBCodec, LMDBCollection, LMDBKodec, StorageSystemError, StorageUserError}
import zio.stream.Stream
import io.scalaland.chimney.dsl.*

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
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

  override def mediaGet(key: MediaAccessKey, ownerId: Option[OwnerId]): IO[ServiceIssue, Option[Media]] = ???

  override def mediaUpdate(
    key: MediaAccessKey,
    eventId: Option[EventId],
    description: Option[MediaDescription],
    starred: Starred,
    keywords: Set[Keyword],
    orientation: Option[Orientation],
    shootDateTime: Option[ShootDateTime], // If updated the key:MediaAccessKey will be updated
    location: Option[Location]
  ): IO[ServiceIssue, Option[Media]] = ???

  override def mediaNormalizedRead(key: MediaAccessKey): IO[ServiceIssue, stream.Stream[ServiceStreamIssue, Byte]] = ???

  override def mediaOriginalRead(key: MediaAccessKey): IO[ServiceIssue, stream.Stream[ServiceStreamIssue, Byte]] = ???

  override def mediaMiniatureRead(key: MediaAccessKey): IO[ServiceIssue, stream.Stream[ServiceStreamIssue, Byte]] = ???

  // -------------------------------------------------------------------------------------------------------------------

  def daoEvent2Event(daoEvent: DaoEvent): IO[ServiceIssue, Event] = {
    for {
      maybeAttachment <- ZIO
                           .foreach(daoEvent.attachment) { daoAttachment =>
                             storeGet(daoAttachment.storeId).map { maybeStore =>
                               maybeStore.map(store => EventAttachment(store, daoAttachment.eventMediaDirectory))
                             }
                           }
                           .map(_.flatten)
    } yield Event(
      id = daoEvent.id,
      attachment = maybeAttachment,
      name = daoEvent.name,
      description = daoEvent.description,
      keywords = daoEvent.keywords
    )
  }

  override def eventList(): IO[ServiceIssue, Stream[ServiceStreamIssue, Event]] = ZIO.succeed {
    events
      .stream()
      .tap(daoEvent => ZIO.succeed(println(s"daoEvent : $daoEvent")))
      .mapZIO(daoEvent2Event)
      .mapError(err => ServiceStreamInternalIssue(s"Couldn't collect events : $err"))
  }

  override def eventGet(eventId: EventId): IO[ServiceIssue, Option[Event]] = for {
    foundDaoEvent <- events.fetch(eventId).mapError(err => ServiceDatabaseIssue(s"Couldn't fetch event : $err"))
    foundEvent    <- ZIO.foreach(foundDaoEvent)(daoEvent2Event)
  } yield foundEvent

  override def eventDelete(eventId: EventId): IO[ServiceIssue, Unit] = {
    events
      .delete(eventId)
      .mapError(err => ServiceDatabaseIssue(s"Couldn't delete event : $err"))
      .unit
  }

  override def eventCreate(attachment: Option[EventAttachment], name: EventName, description: Option[EventDescription], keywords: Set[Keyword]): IO[ServiceIssue, Event] = {
    println(s"eventCreate : attachment=$attachment, name=$name, description=$description, keywords=$keywords")
    for {
      eventId <- Random.nextUUID.map(EventId.apply)
      event    = Event(eventId, attachment, name, description, keywords)
      _       <- events
                   .upsert(eventId, _ => event.transformInto[DaoEvent])
                   .mapError(err => ServiceDatabaseIssue(s"Couldn't create event : $err"))
    } yield event
  }

  override def eventUpdate(eventId: EventId, attachment: Option[EventAttachment], name: EventName, description: Option[EventDescription], keywords: Set[Keyword]): IO[ServiceIssue, Option[Event]] = {
    for {
      foundDaoEvent <- events
                         .update(eventId, _.copy(attachment = attachment.transformInto[Option[DaoEventAttachment]], name = name, description = description, keywords = keywords))
                         .mapError(err => ServiceDatabaseIssue(s"Couldn't update owner : $err"))
      event         <- ZIO.foreach(foundDaoEvent)(daoEvent2Event)
    } yield event

  }

  // -------------------------------------------------------------------------------------------------------------------

  override def ownerList(): IO[ServiceIssue, List[Owner]] = {
    owners
      .stream()
      .map(daoOwner => daoOwner.transformInto[Owner])
      .runCollect
      .mapBoth(err => ServiceDatabaseIssue(s"Couldn't collect owners : $err"), _.toList)
  }

  override def ownerGet(ownerId: OwnerId): IO[ServiceIssue, Option[Owner]] = {
    owners
      .fetch(ownerId)
      .mapBoth(err => ServiceDatabaseIssue(s"Couldn't fetch owner : $err"), foundDaoOwner => foundDaoOwner.map(_.transformInto[Owner]))
  }

  override def ownerDelete(ownerId: OwnerId): IO[ServiceIssue, Unit] = {
    owners
      .delete(ownerId)
      .mapError(err => ServiceDatabaseIssue(s"Couldn't delete owner : $err"))
      .unit
  }

  override def ownerCreate(providedOwnerId: Option[OwnerId], firstName: FirstName, lastName: LastName, birthDate: Option[BirthDate]): IO[ServiceIssue, Owner] = {
    for {
      ownerId <- ZIO
                   .from(providedOwnerId)
                   .orElse(ZIO.attempt(OwnerId(ULID.newULID)))
                   .mapError(err => ServiceInternalIssue(s"Unable to create an owner identifier : $err"))
      owner    = Owner(ownerId, firstName, lastName, birthDate)
      _       <- owners
                   .upsert(owner.id, _ => owner.transformInto[DaoOwner])
                   .mapError(err => ServiceDatabaseIssue(s"Couldn't create owner : $err"))
    } yield owner
  }

  override def ownerUpdate(ownerId: OwnerId, firstName: FirstName, lastName: LastName, birthDate: Option[BirthDate]): IO[ServiceIssue, Option[Owner]] = {
    for {
      foundDaoOwner <- owners
                         .update(ownerId, _.copy(firstName = firstName, lastName = lastName, birthDate = birthDate))
                         .mapError(err => ServiceDatabaseIssue(s"Couldn't update owner : $err"))
      owner          = foundDaoOwner.map(_.transformInto[Owner])
    } yield owner
  }

  // -------------------------------------------------------------------------------------------------------------------

  override def storeList(): IO[ServiceIssue, List[Store]] = {
    stores
      .stream()
      .map(daoStore => daoStore.transformInto[Store])
      .runCollect
      .mapBoth(err => ServiceDatabaseIssue(s"Couldn't collect stores : $err"), _.toList)
  }

  override def storeGet(storeId: StoreId): IO[ServiceIssue, Option[Store]] = {
    stores
      .fetch(storeId)
      .mapBoth(err => ServiceDatabaseIssue(s"Couldn't fetch store : $err"), foundDaoStore => foundDaoStore.map(_.transformInto[Store]))
  }

  override def storeDelete(storeId: StoreId): IO[ServiceIssue, Unit] = {
    stores
      .delete(storeId)
      .mapError(err => ServiceDatabaseIssue(s"Couldn't delete store : $err"))
      .unit
  }

  override def storeCreate(providedStoreId: Option[StoreId], ownerId: OwnerId, baseDirectory: BaseDirectoryPath, includeMask: Option[IncludeMask], ignoreMask: Option[IgnoreMask]): IO[ServiceIssue, Store] = {
    for {
      storeId <- ZIO
                   .from(providedStoreId)
                   .orElse(ZIO.attempt(StoreId(UUID.randomUUID())))
                   .mapError(err => ServiceInternalIssue(s"Unable to create a store identifier : $err"))
      store    = Store(storeId, ownerId, baseDirectory, includeMask, ignoreMask)
      _       <- stores
                   .upsert(store.id, _ => store.transformInto[DaoStore])
                   .mapError(err => ServiceDatabaseIssue(s"Couldn't create store : $err"))
    } yield store
  }

  override def storeUpdate(storeId: StoreId, includeMask: Option[IncludeMask], ignoreMask: Option[IgnoreMask]): IO[ServiceIssue, Option[Store]] = {
    for {
      foundDaoStore <- stores
                         .update(storeId, _.copy(includeMask = includeMask, ignoreMask = ignoreMask))
                         .mapError(err => ServiceDatabaseIssue(s"Couldn't update store : $err"))
      store          = foundDaoStore.map(_.transformInto[Store])
    } yield store
  }

  // -------------------------------------------------------------------------------------------------------------------

  override def synchronize(): IO[ServiceIssue, Unit] = {
    ???
  }
}

object MediaServiceLive {
  val charset = StandardCharsets.UTF_8 // TODO improve charset support

  // -------------------------------------------------------------------------------------------------------------------
  private def uuidBytesToEither(uuidBytes: ByteBuffer): Either[String, UUID] = Try {
    UUID.fromString(charset.decode(uuidBytes).toString)
  } match {
    case Failure(exception) => Left(exception.getMessage)
    case Success(uuid)      => Right(uuid)
  }

  // -------------------------------------------------------------------------------------------------------------------
  private def ulidBytesToEither(ulidBytes: ByteBuffer): Either[String, ULID] = Try {
    ULID.fromString(charset.decode(ulidBytes).toString)
  } match {
    case Failure(exception) => Left(exception.getMessage)
    case Success(ulid)      => Right(ulid)
  }

  // -------------------------------------------------------------------------------------------------------------------
  given LMDBKodec[OriginalId] = new LMDBKodec {
    def encode(key: OriginalId): Array[Byte]                     = key.asString.getBytes(charset.name())
    def decode(keyBytes: ByteBuffer): Either[String, OriginalId] = uuidBytesToEither(keyBytes).map(OriginalId.apply)
  }

  // -------------------------------------------------------------------------------------------------------------------
  given LMDBKodec[EventId] = new LMDBKodec {
    def encode(key: EventId): Array[Byte]                     = key.asString.getBytes(charset.name())
    def decode(keyBytes: ByteBuffer): Either[String, EventId] = uuidBytesToEither(keyBytes).map(EventId.apply)
  }

  // -------------------------------------------------------------------------------------------------------------------
  given LMDBKodec[MediaAccessKey] = new LMDBKodec {
    def encode(key: MediaAccessKey): Array[Byte]                     = key.asString.getBytes(charset.name())
    def decode(keyBytes: ByteBuffer): Either[String, MediaAccessKey] = ulidBytesToEither(keyBytes).map(MediaAccessKey.apply)
  }

  // -------------------------------------------------------------------------------------------------------------------
  given LMDBKodec[OwnerId] = new LMDBKodec {
    def encode(key: OwnerId): Array[Byte]                     = key.asString.getBytes(charset.name())
    def decode(keyBytes: ByteBuffer): Either[String, OwnerId] = ulidBytesToEither(keyBytes).map(OwnerId.apply)
  }

  // -------------------------------------------------------------------------------------------------------------------
  given LMDBKodec[StoreId] = new LMDBKodec {
    def encode(key: StoreId): Array[Byte]                     = key.asString.getBytes(charset.name())
    def decode(keyBytes: ByteBuffer): Either[String, StoreId] = uuidBytesToEither(keyBytes).map(StoreId.apply)
  }

  // -------------------------------------------------------------------------------------------------------------------
  given LMDBCodec[MediaAccessKey] = new LMDBCodec {
    def encode(key: MediaAccessKey): Array[Byte]                     = key.asString.getBytes(charset.name())
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
