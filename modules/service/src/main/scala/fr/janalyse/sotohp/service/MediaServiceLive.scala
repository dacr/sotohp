package fr.janalyse.sotohp.service

import fr.janalyse.sotohp.media.core.{FileSystemSearch, MediaBuilder, OriginalBuilder}
import fr.janalyse.sotohp.media.model.*
import fr.janalyse.sotohp.service.dao.*
import wvlet.airframe.ulid.ULID
import zio.*
import zio.lmdb.{LMDB, LMDBCodec, LMDBCollection, LMDBKodec, StorageSystemError, StorageUserError}
import zio.stream.{Stream, ZStream}
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

  def daoMedia2Media(daoMedia: DaoMedia): IO[ServiceIssue, Media] = {
    for {
      original <- originalGet(daoMedia.originalId).someOrFail(ServiceDatabaseIssue(s"Couldn't find original : ${daoMedia.originalId}"))
      events   <- ZIO.foreach(daoMedia.events)(eventId => eventGet(eventId).some.mapError(err => ServiceDatabaseIssue(s"Couldn't fetch event : $err")))
      media     = daoMedia
                    .into[Media]
                    .withFieldConst(_.original, original)
                    .withFieldConst(_.events, events.toList)
                    .transform
    } yield media
  }

  override def mediaList(): Stream[ServiceStreamIssue, Media] = {
    medias
      .stream()
      .mapZIO(daoMedia2Media)
      .mapError(err => ServiceStreamInternalIssue(s"Couldn't collect medias : $err"))
  }

  override def mediaFind(nearKey: MediaAccessKey, ownerId: Option[OwnerId]): IO[ServiceIssue, Option[Media]] = ???

  override def mediaSearch(keywordsFilter: Set[Keyword], ownerId: Option[OwnerId]): Stream[ServiceStreamIssue, Media] = ???

  override def mediaFirst(ownerId: Option[OwnerId]): IO[ServiceIssue, Option[Media]] = ???

  override def mediaPrevious(nearKey: MediaAccessKey, ownerId: Option[OwnerId]): IO[ServiceIssue, Option[Media]] = ???

  override def mediaNext(nearKey: MediaAccessKey, ownerId: Option[OwnerId]): IO[ServiceIssue, Option[Media]] = ???

  override def mediaLast(ownerId: Option[OwnerId]): IO[ServiceIssue, Option[Media]] = ???

  override def mediaGet(key: MediaAccessKey): IO[ServiceIssue, Option[Media]] = {
    medias
      .fetch(key)
      .mapError(err => ServiceDatabaseIssue(s"Couldn't fetch media : $err"))
      .flatMap(maybeDaoMedia => ZIO.foreach(maybeDaoMedia)(daoMedia2Media))
  }

  override def mediaUpdate(
    key: MediaAccessKey,
    updatedMedia: Media
  ): IO[ServiceIssue, Option[Media]] = ???

  // -------------------------------------------------------------------------------------------------------------------
  override def mediaNormalizedRead(key: MediaAccessKey): Stream[ServiceStreamIssue, Byte] = ???

  override def mediaOriginalRead(key: MediaAccessKey): Stream[ServiceStreamIssue, Byte] = ???

  override def mediaMiniatureRead(key: MediaAccessKey): Stream[ServiceStreamIssue, Byte] = ???

  // -------------------------------------------------------------------------------------------------------------------
  def stateList(): Stream[ServiceStreamIssue, State]                             = {
    states
      .stream()
      .map(daoState => daoState.transformInto[State])
      .mapError(err => ServiceStreamInternalIssue(s"Couldn't collect states : $err"))
  }
  def stateGet(originalId: OriginalId): IO[ServiceIssue, Option[State]]          = {
    states
      .fetch(originalId)
      .map(maybeDaoState => maybeDaoState.map(_.transformInto[State]))
      .mapError(err => ServiceDatabaseIssue(s"Couldn't fetch state : $err"))
  }
  def stateDelete(originalId: OriginalId): IO[ServiceIssue, Unit]                = {
    states
      .delete(originalId)
      .mapError(err => ServiceDatabaseIssue(s"Couldn't delete state : $err"))
      .unit
  }
  def stateUpsert(originalId: OriginalId, state: State): IO[ServiceIssue, State] = {
    states
      .upsert(originalId, _ => state.transformInto[DaoState])
      .mapError(err => ServiceDatabaseIssue(s"Couldn't create or update state : $err"))
      .as(state)
  }

  // -------------------------------------------------------------------------------------------------------------------

  def daoOriginal2Original(daoOriginal: DaoOriginal): IO[ServiceIssue, Original] = {
    for {
      store   <- storeGet(daoOriginal.storeId).someOrFail(ServiceDatabaseIssue(s"Couldn't find store for original : ${daoOriginal.storeId}"))
      original = daoOriginal.into[Original].withFieldConst(_.store, store).transform
    } yield original
  }

  override def originalList(): Stream[ServiceStreamIssue, Original] = {
    originals
      .stream()
      .mapZIO(daoOriginal2Original)
      .mapError(err => ServiceStreamInternalIssue(s"Couldn't collect originals : $err"))
  }

  override def originalGet(originalId: OriginalId): IO[ServiceIssue, Option[Original]] = for {
    maybeDaoOriginal <- originals.fetch(originalId).mapError(err => ServiceDatabaseIssue(s"Couldn't fetch original : $err"))
    maybeOriginal    <- ZIO.foreach(maybeDaoOriginal)(daoOriginal2Original)
  } yield maybeOriginal

  override def originalExists(originalId: OriginalId): IO[ServiceIssue, Boolean] =
    originals.contains(originalId).mapError(err => ServiceDatabaseIssue(s"Couldn't lookup original : $err"))

  override def originalDelete(originalId: OriginalId): IO[ServiceIssue, Unit] = {
    originals
      .delete(originalId)
      .mapError(err => ServiceDatabaseIssue(s"Couldn't delete original : $err"))
      .unit
  }

  override def originalUpsert(providedOriginal: Original): IO[ServiceIssue, Original] = {
    originals
      .upsert(providedOriginal.id, previous => providedOriginal.into[DaoOriginal].transform)
      .mapError(err => ServiceDatabaseIssue(s"Couldn't create or update original : $err"))
      .as(providedOriginal)
  }

  // -------------------------------------------------------------------------------------------------------------------

  def daoEvent2Event(daoEvent: DaoEvent): IO[ServiceIssue, Event] = {
    for {
      maybeAttachment <- ZIO
                           .foreach(daoEvent.attachment) { daoAttachment =>
                             storeGet(daoAttachment.storeId)
                               .map { maybeStore =>
                                 maybeStore.map(store => EventAttachment(store, daoAttachment.eventMediaDirectory))
                               }
                               .someOrFail(ServiceDatabaseIssue(s"Couldn't find store for event attachment : ${daoAttachment.storeId}"))
                           }
      event            = daoEvent.into[Event].withFieldConst(_.attachment, maybeAttachment).transform
    } yield event
  }

  override def eventList(): Stream[ServiceStreamIssue, Event] = {
    events
      .stream()
      .mapZIO(daoEvent2Event)
      .mapError(err => ServiceStreamInternalIssue(s"Couldn't collect events : $err"))
  }

  override def eventGet(eventId: EventId): IO[ServiceIssue, Option[Event]] = for {
    maybeDaoEvent <- events.fetch(eventId).mapError(err => ServiceDatabaseIssue(s"Couldn't fetch event : $err"))
    maybeEvent    <- ZIO.foreach(maybeDaoEvent)(daoEvent2Event)
  } yield maybeEvent

  override def eventDelete(eventId: EventId): IO[ServiceIssue, Unit] = {
    events
      .delete(eventId)
      .mapError(err => ServiceDatabaseIssue(s"Couldn't delete event : $err"))
      .unit
  }

  override def eventCreate(attachment: Option[EventAttachment], name: EventName, description: Option[EventDescription], keywords: Set[Keyword]): IO[ServiceIssue, Event] = {
    for {
      eventId <- Random.nextUUID.map(EventId.apply)
      event    = Event(eventId, attachment, name, description, keywords)
      _       <- events
                   .upsert(eventId, _ => event.into[DaoEvent].transform)
                   .mapError(err => ServiceDatabaseIssue(s"Couldn't create event : $err"))
    } yield event
  }

  override def eventUpdate(eventId: EventId, attachment: Option[EventAttachment], name: EventName, description: Option[EventDescription], keywords: Set[Keyword]): IO[ServiceIssue, Option[Event]] = {
    for {
      maybeDaoEvent <- events
                         .update(eventId, _.copy(attachment = attachment.transformInto[Option[DaoEventAttachment]], name = name, description = description, keywords = keywords))
                         .mapError(err => ServiceDatabaseIssue(s"Couldn't update owner : $err"))
      event         <- ZIO.foreach(maybeDaoEvent)(daoEvent2Event)
    } yield event

  }

  // -------------------------------------------------------------------------------------------------------------------

  override def ownerList(): Stream[ServiceIssue, Owner] = {
    owners
      .stream()
      .mapBoth(err => ServiceDatabaseIssue(s"Couldn't collect owners : $err"), daoOwner => daoOwner.transformInto[Owner])
  }

  override def ownerGet(ownerId: OwnerId): IO[ServiceIssue, Option[Owner]] = {
    owners
      .fetch(ownerId)
      .mapBoth(err => ServiceDatabaseIssue(s"Couldn't fetch owner : $err"), maybeDaoOwner => maybeDaoOwner.map(_.transformInto[Owner]))
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
      maybeDaoOwner <- owners
                         .update(ownerId, _.copy(firstName = firstName, lastName = lastName, birthDate = birthDate))
                         .mapError(err => ServiceDatabaseIssue(s"Couldn't update owner : $err"))
      maybeOwner     = maybeDaoOwner.map(_.transformInto[Owner])
    } yield maybeOwner
  }

  // -------------------------------------------------------------------------------------------------------------------

  override def storeList(): Stream[ServiceIssue, Store] = {
    stores
      .stream()
      .map(daoStore => daoStore.transformInto[Store])
      .mapError(err => ServiceDatabaseIssue(s"Couldn't collect stores : $err"))
  }

  override def storeGet(storeId: StoreId): IO[ServiceIssue, Option[Store]] = {
    stores
      .fetch(storeId)
      .mapBoth(err => ServiceDatabaseIssue(s"Couldn't fetch store : $err"), maybeDaoStore => maybeDaoStore.map(_.transformInto[Store]))
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
      maybeDaoStore <- stores
                         .update(storeId, _.copy(includeMask = includeMask, ignoreMask = ignoreMask))
                         .mapError(err => ServiceDatabaseIssue(s"Couldn't update store : $err"))
      maybeStore     = maybeDaoStore.map(_.transformInto[Store])
    } yield maybeStore
  }

  // -------------------------------------------------------------------------------------------------------------------

  private def synchronizeOriginal(original: Original): IO[ServiceIssue, Original] = {
    for {
      available <- originalExists(original.id)
      _         <- originalUpsert(original).when(!available)
    } yield original
  }

  /** Generate if needed original related artifacts (miniatures & normalized original)
    * @param original
    * @return
    *   given original
    */
  private def synchronizeArtifacts(original: Original): IO[ServiceIssue, Original] = {
    // TODO
    ZIO.succeed(original)
  }

  private def synchronizeState(original: Original): IO[ServiceIssue, (original: Original, state: State)] = {
    for {
      currentState <- stateGet(original.id)
      now          <- Clock.currentDateTime
      updatedState  = currentState
                        .map(_.copy(originalLastChecked = LastChecked(now)))
                        .getOrElse(
                          State(
                            originalId = original.id,
                            originalHash = None,
                            originalAddedOn = AddedOn(now),
                            originalLastChecked = LastChecked(now),
                            mediaAccessKey = MediaBuilder.buildDefaultMediaAccessKey(original),
                            mediaLastSynchronized = None
                          )
                        )
      state        <- stateUpsert(original.id, updatedState)
    } yield (original, state)
  }

  private def getEventForAttachment(attachment: EventAttachment): IO[ServiceIssue, Option[Event]] = {
    // TODO first basic and naive implementation - not good for complexity
    events
      .collect(valueFilter = daoFilter => daoFilter.attachment.exists(thatAttachment => thatAttachment.storeId == attachment.store.id && thatAttachment.eventMediaDirectory == attachment.eventMediaDirectory))
      .mapBoth(err => ServiceDatabaseIssue(s"Couldn't collect events : $err"), _.headOption)
      .flatMap(mayBeDaoEvent => ZIO.foreach(mayBeDaoEvent)(daoEvent2Event))
  }

  private def createDefaultEvent(attachment: EventAttachment): IO[ServiceIssue, Event] = {
    // TODO add automatic keywords extraction
    eventCreate(attachment = Some(attachment), name = EventName(attachment.eventMediaDirectory.toString), description = None, keywords = Set.empty)
  }

  private def synchronizeMedia(input: (original: Original, state: State)): IO[ServiceIssue, (media: Media, state: State)] = {
    val relatedEventAttachment = MediaBuilder.buildEventAttachment(input.original)
    for {
      mayBeEvent   <- ZIO
                        .foreach(relatedEventAttachment)(attachment => getEventForAttachment(attachment).someOrElseZIO(createDefaultEvent(attachment)))
      currentMedia <- mediaGet(input.state.mediaAccessKey) // already existing media is the source of truth !
                        .someOrElseZIO {
                          val daoMedia = DaoMedia(
                            accessKey = input.state.mediaAccessKey,
                            originalId = input.original.id,
                            events = mayBeEvent.map(_.id).toSet,
                            description = None,
                            starred = Starred(false),
                            keywords = Set.empty,
                            orientation = None,
                            shootDateTime = None,
                            location = None
                          )
                          medias
                            .upsert(input.state.mediaAccessKey, _ => daoMedia)
                            .flatMap(daoMedia2Media)
                            .mapError(err => ServiceDatabaseIssue(s"Couldn't create media : $err"))
                        }
    } yield (currentMedia, input.state)
  }

  private def synchronizeSearchEngine(input: (media: Media, state: State)): IO[ServiceIssue, (media: Media, state: State)] = {
    // TODO to implement
    ZIO.succeed(input)
  }

  override def synchronize(): IO[ServiceIssue, Unit] = {
    storeList()
      .mapZIO(store => ZIO.from(FileSystemSearch.originalsStreamFromSearchRoot(store)))
      .flatMap(javaStream => ZStream.fromJavaStream(javaStream))
      .right
      .mapZIO(synchronizeOriginal)
      .mapZIO(synchronizeArtifacts)
      .mapZIO(synchronizeState)
      .mapZIO(synchronizeMedia)
      .filter(_.state.mediaLastSynchronized.isEmpty)
      .mapZIO(synchronizeSearchEngine)
      .runDrain
      .mapError(err => ServiceInternalIssue(s"Unable to synchronize : $err"))
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
  private def stringBytesToEither(bytes: ByteBuffer): Either[String, String] = Try {
    charset.decode(bytes).toString
  } match {
    case Failure(exception) => Left(exception.getMessage)
    case Success(str)       => Right(str)
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
    def decode(keyBytes: ByteBuffer): Either[String, MediaAccessKey] = stringBytesToEither(keyBytes).map(MediaAccessKey.apply)
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
    def decode(keyBytes: ByteBuffer): Either[String, MediaAccessKey] = stringBytesToEither(keyBytes).map(MediaAccessKey.apply)
  }

  // -------------------------------------------------------------------------------------------------------------------
  private val originalsCollectionName = "originals"
  private val statesCollectionName    = "states"
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
