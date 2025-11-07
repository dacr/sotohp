package fr.janalyse.sotohp.service

import fr.janalyse.sotohp.core.{CoreIssue, FileSystemSearch, FileSystemSearchCoreConfig, HashOperations, MediaBuilder, OriginalBuilder}
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.processor.{ClassificationIssue, ClassificationProcessor, FacesDetectionIssue, FacesProcessor, MiniaturizeProcessor, NormalizeProcessor, ObjectsDetectionIssue, ObjectsDetectionProcessor}
import fr.janalyse.sotohp.processor.model.*
import fr.janalyse.sotohp.search.SearchService
import fr.janalyse.sotohp.search.model.MediaBag
import json.*
import fr.janalyse.sotohp.service.dao.*
import fr.janalyse.sotohp.service.model.*
import fr.janalyse.sotohp.service.model.SynchronizeAction.{Stop, WaitForCompletion}
import wvlet.airframe.ulid.ULID
import zio.*
import zio.lmdb.{LMDB, LMDBCodec, LMDBCollection, LMDBKodec, StorageSystemError, StorageUserError}
import zio.stream.{Stream, ZStream}
import io.scalaland.chimney.dsl.*
import zio.ZIOAspect.annotated

import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.util.UUID
import java.util.regex.Pattern
import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}

type LMDBIssues = StorageUserError | StorageSystemError

class MediaServiceLive private (
  lmdb: LMDB,
  search: SearchService,
  // ------------------------
  originalsColl: LMDBCollection[OriginalId, DaoOriginal],
  statesColl: LMDBCollection[OriginalId, DaoState],
  eventsColl: LMDBCollection[EventId, DaoEvent],
  mediasColl: LMDBCollection[MediaAccessKey, DaoMedia],
  ownersColl: LMDBCollection[OwnerId, DaoOwner],
  storesColl: LMDBCollection[StoreId, DaoStore],
  keywordRulesColl: LMDBCollection[StoreId, DaoKeywordRules],
  classificationsColl: LMDBCollection[OriginalId, DaoOriginalClassifications],
  detectedFaceColl: LMDBCollection[FaceId, DaoDetectedFace],
  originalFoundFacesColl: LMDBCollection[OriginalId, DaoOriginalFaces],
  objectsColl: LMDBCollection[OriginalId, DaoOriginalDetectedObjects],
  miniaturesColl: LMDBCollection[OriginalId, DaoOriginalMiniatures],
  normalizedColl: LMDBCollection[OriginalId, DaoOriginalNormalized],
  personsColl: LMDBCollection[PersonId, DaoPerson],
  // ------------------------
  classificationProcessorEffect: IO[ClassificationIssue, ClassificationProcessor],
  facesProcessorEffect: IO[FacesDetectionIssue, FacesProcessor],
  objectsProcessorEffect: IO[ObjectsDetectionIssue, ObjectsDetectionProcessor],
  // ------------------------
  synchronizeStatusRef: Ref[SynchronizeStatus],
  synchronizeFiberRef: Ref[Option[Fiber[ServiceIssue, Unit]]]
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
    mediasColl
      .stream()
      .mapZIO(daoMedia2Media)
      .mapError(err => ServiceStreamInternalIssue(s"Couldn't collect medias : $err"))
  }

  override def mediaFind(nearKey: MediaAccessKey): IO[ServiceIssue, Option[Media]] = ???

  override def mediaSearch(keywordsFilter: Set[Keyword]): Stream[ServiceStreamIssue, Media] = ???

  override def mediaFirst(): IO[ServiceIssue, Option[Media]] = {
    mediasColl
      .head()
      .map(result => result.map((key, media) => media))
      .flatMap(mayBeDaoMedia => ZIO.foreach(mayBeDaoMedia)(daoMedia2Media))
      .mapError(err => ServiceDatabaseIssue(s"Couldn't get first media : $err"))
  }

  override def mediaPrevious(nearKey: MediaAccessKey): IO[ServiceIssue, Option[Media]] = {
    mediasColl
      .previous(nearKey)
      .map(result => result.map((key, media) => media))
      .flatMap(mayBeDaoMedia => ZIO.foreach(mayBeDaoMedia)(daoMedia2Media))
      .mapError(err => ServiceDatabaseIssue(s"Couldn't get previous media : $err"))
  }

  override def mediaNext(nearKey: MediaAccessKey): IO[ServiceIssue, Option[Media]] = {
    mediasColl
      .next(nearKey)
      .map(result => result.map((key, media) => media))
      .flatMap(mayBeDaoMedia => ZIO.foreach(mayBeDaoMedia)(daoMedia2Media))
      .mapError(err => ServiceDatabaseIssue(s"Couldn't get next media : $err"))
  }

  override def mediaLast(): IO[ServiceIssue, Option[Media]] = {
    mediasColl
      .last()
      .map(result => result.map((key, media) => media))
      .flatMap(mayBeDaoMedia => ZIO.foreach(mayBeDaoMedia)(daoMedia2Media))
      .mapError(err => ServiceDatabaseIssue(s"Couldn't get last media : $err"))
  }

  override def mediaGet(key: MediaAccessKey): IO[ServiceIssue, Option[Media]] = {
    mediasColl
      .fetch(key)
      .mapError(err => ServiceDatabaseIssue(s"Couldn't fetch media : $err"))
      .flatMap(maybeDaoMedia => ZIO.foreach(maybeDaoMedia)(daoMedia2Media))
  }

  override def mediaGetAt(index: Long): IO[ServiceIssue, Option[Media]] = {
    mediasColl
      .fetchAt(index)
      .provideEnvironment(ZEnvironment(lmdb))
      .mapError(err => ServiceDatabaseIssue(s"Couldn't fetch random issue media : $err"))
      .map(result => result.map((key, media) => media))
      .flatMap(mayBeDaoMedia => ZIO.foreach(mayBeDaoMedia)(daoMedia2Media))
  }

  override def mediaUpdate(
    key: MediaAccessKey,
    updatedMedia: Media
  ): IO[ServiceIssue, Option[Media]] = {
    if (key == updatedMedia.accessKey) {
      mediasColl
        .update(key, _ => updatedMedia.transformInto[DaoMedia](using DaoMedia.transformer)) // to solve ambiguity with auto-derived transformer
        .mapError(err => ServiceDatabaseIssue(s"Couldn't update media : $err"))
        .flatMap(mayBeDaoMedia => ZIO.foreach(mayBeDaoMedia)(daoMedia2Media))
    } else {
      // TODO require transactions
      // key has been modified require delete record & then insert with the new access key
      // TODO dangerous operation in particular because no transaction to ensure coherency, making it uninterrruptible is not enough
      (mediasColl.delete(key).unit *> mediasColl.upsert(updatedMedia.accessKey, _ => updatedMedia.transformInto[DaoMedia](using DaoMedia.transformer))).uninterruptible
        .mapError(err => ServiceDatabaseIssue(s"Couldn't update media : $err"))
        .flatMap(daoMedia => daoMedia2Media(daoMedia).option)
    }
    // TODO resync published
  }

  // -------------------------------------------------------------------------------------------------------------------
  override def mediaNormalizedRead(key: MediaAccessKey): Stream[ServiceStreamIssue, Byte] = {
    val pathEffect: IO[ServiceStreamIssue, java.nio.file.Path] = for {
      media <- mediaGet(key)
                 .mapError(err => ServiceStreamInternalIssue(s"Couldn't fetch media for key ${key.asString} : $err"))
                 .someOrFail(ServiceStreamInternalIssue(s"Couldn't find media for key : ${key.asString}"))
      onorm <- originalNormalized(media.original.id)
                 .mapError(err => ServiceStreamInternalIssue(s"Couldn't retrieve normalized info for original ${media.original.id.asString} : $err"))
                 .someOrFail(ServiceStreamInternalIssue(s"Couldn't get normalized information for original : ${media.original.id.asString}"))
      norm  <- ZIO
                 .fromOption(onorm.normalized)
                 .mapError(_ => ServiceStreamInternalIssue(s"Normalized image not available for original : ${media.original.id.asString}"))
      path   = norm.path.path
    } yield path

    ZStream.unwrapScoped {
      pathEffect.map { path =>
        ZStream
          .fromInputStreamZIO(ZIO.attemptBlockingIO(new java.io.FileInputStream(path.toFile)))
          .mapError(th => ServiceStreamInternalIssue(s"Couldn't open/read normalized image file $path : $th"))
      }
    }
  }

  override def mediaOriginalRead(key: MediaAccessKey): Stream[ServiceStreamIssue, Byte] = {
    val pathEffect: IO[ServiceStreamIssue, java.nio.file.Path] = for {
      media <- mediaGet(key)
                 .mapError(err => ServiceStreamInternalIssue(s"Couldn't fetch media for key ${key.asString} : $err"))
                 .someOrFail(ServiceStreamInternalIssue(s"Couldn't find media for key : ${key.asString}"))
    } yield media.original.absoluteMediaPath

    ZStream.unwrapScoped {
      pathEffect.map { path =>
        ZStream
          .fromInputStreamZIO(ZIO.attemptBlockingIO(new java.io.FileInputStream(path.toFile)))
          .mapError(th => ServiceStreamInternalIssue(s"Couldn't open/read original image file $path : $th"))
      }
    }
  }

  override def mediaMiniatureRead(key: MediaAccessKey): Stream[ServiceStreamIssue, Byte] = {
    import fr.janalyse.sotohp.processor.MiniaturizeProcessor
    import fr.janalyse.sotohp.processor.config.MiniaturizerConfig

    ZStream.unwrapScoped {
      val streamEff: IO[ServiceStreamIssue, ZStream[Any, ServiceStreamIssue, Byte]] = (for {
        media  <- mediaGet(key)
                    .mapError(err => ServiceStreamInternalIssue(s"Couldn't fetch media for key ${key.asString} : $err"))
                    .someOrFail(ServiceStreamInternalIssue(s"Couldn't find media for key : ${key.asString}"))
        // ensure miniatures info is computed/stored (best effort)
        _      <- originalMiniatures(media.original.id).either
        sizes  <- MiniaturizerConfig.config.map(_.referenceSizes).mapError(err => ServiceStreamInternalIssue(err.toString))
        size    = sizes.maxOption.getOrElse(256)
        path   <- MiniaturizeProcessor
                    .getOriginalMiniatureFilePath(media.original, size)
                    .mapError(err => ServiceStreamInternalIssue(s"Couldn't compute miniature path: $err"))
        exists <- ZIO.attempt(path.toFile.exists()).mapError(th => ServiceStreamInternalIssue(s"Couldn't check file existence $path : $th"))
        stream <- if (exists) {
                    ZIO.succeed(
                      ZStream
                        .fromInputStreamZIO(ZIO.attemptBlockingIO(new java.io.FileInputStream(path.toFile)))
                        .mapError(th => ServiceStreamInternalIssue(s"Couldn't open/read miniature image file $path : $th"))
                    )
                  } else {
                    // fallback to normalized then original
                    ZIO.succeed(mediaNormalizedRead(key))
                  }
      } yield stream)

      streamEff.orElseSucceed(mediaOriginalRead(key))
    }
  }

  // -------------------------------------------------------------------------------------------------------------------
  def stateList(): Stream[ServiceStreamIssue, State]                             = {
    statesColl
      .stream()
      .map(daoState => daoState.transformInto[State])
      .mapError(err => ServiceStreamInternalIssue(s"Couldn't collect states : $err"))
  }
  def stateGet(originalId: OriginalId): IO[ServiceIssue, Option[State]]          = {
    statesColl
      .fetch(originalId)
      .map(maybeDaoState => maybeDaoState.map(_.transformInto[State]))
      .mapError(err => ServiceDatabaseIssue(s"Couldn't fetch state : $err"))
  }
  def stateDelete(originalId: OriginalId): IO[ServiceIssue, Unit]                = {
    statesColl
      .delete(originalId)
      .mapError(err => ServiceDatabaseIssue(s"Couldn't delete state : $err"))
      .unit
  }
  def stateUpsert(originalId: OriginalId, state: State): IO[ServiceIssue, State] = {
    statesColl
      .upsert(originalId, _ => state.transformInto[DaoState])
      .mapError(err => ServiceDatabaseIssue(s"Couldn't create or update state : $err"))
      .as(state)
  }

  // -------------------------------------------------------------------------------------------------------------------

  def faceList(): Stream[ServiceStreamIssue, DetectedFace] = {
    detectedFaceColl
      .stream()
      .map(daoFace => daoFace.transformInto[DetectedFace])
      .mapError(err => ServiceStreamInternalIssue(s"Couldn't collect medias : $err"))
  }

  def faceCount(): IO[ServiceIssue, Long] = {
    detectedFaceColl
      .size()
      .mapError(err => ServiceDatabaseIssue(s"Couldn't count faces : $err"))
  }

  def faceGet(faceId: FaceId): IO[ServiceIssue, Option[DetectedFace]] = {
    detectedFaceColl
      .fetch(faceId)
      .map(maybeDaoFace => maybeDaoFace.map(_.transformInto[DetectedFace]))
      .mapError(err => ServiceDatabaseIssue(s"Couldn't fetch face : $err"))
  }

  def faceExists(faceId: FaceId): IO[ServiceIssue, Boolean] = {
    detectedFaceColl
      .contains(faceId)
      .mapError(err => ServiceDatabaseIssue(s"Couldn't check if face exists : $err"))
  }

  def faceDelete(faceId: FaceId): IO[ServiceIssue, Unit] = {
    detectedFaceColl
      .delete(faceId)
      .mapError(err => ServiceDatabaseIssue(s"Couldn't delete face : $err"))
      .unit
  }

  def faceUpdate(
    faceId: FaceId, // current face id
    face: DetectedFace // may contain and updated id
  ): IO[ServiceIssue, DetectedFace] = {
    if (face.faceId == faceId) {
      detectedFaceColl
        .upsert(faceId, _ => face.transformInto[DaoDetectedFace])
        .mapError(err => ServiceDatabaseIssue(s"Couldn't update face : $err"))
        .as(face)
    } else {
      // TODO require transactions
      // id has been modified require delete record & then insert with the new access key
      // TODO dangerous operation in particular because no transaction to ensure coherency, making it uninterrruptible is not enough
      (detectedFaceColl.delete(faceId).unit *> detectedFaceColl.upsert(face.faceId, _ => face.transformInto[DaoDetectedFace])).uninterruptible
        .mapError(err => ServiceDatabaseIssue(s"Couldn't update face : $err"))
        .as(face)
    }
  }

  def faceRead(faceId: FaceId): Stream[ServiceStreamIssue, Byte] = {
    val pathEffect: IO[ServiceStreamIssue, java.nio.file.Path] = for {
      face <- faceGet(faceId)
                .mapError(err => ServiceStreamInternalIssue(s"Couldn't fetch face for id ${faceId.asString} : $err"))
                .someOrFail(ServiceStreamInternalIssue(s"Couldn't find face for id : ${faceId.asString}"))
      path  = face.path.path
    } yield path

    ZStream.unwrapScoped {
      pathEffect.map { path =>
        ZStream
          .fromInputStreamZIO(ZIO.attemptBlockingIO(new java.io.FileInputStream(path.toFile)))
          .mapError(th => ServiceStreamInternalIssue(s"Couldn't open/read normalized image file $path : $th"))
      }
    }
  }

  // -------------------------------------------------------------------------------------------------------------------

  def personList(): Stream[ServiceStreamIssue, Person] = {
    personsColl
      .stream()
      .map(daoPerson => daoPerson.transformInto[Person])
      .mapError(err => ServiceStreamInternalIssue(s"Couldn't collect persons : $err"))
  }

  def personCount(): IO[ServiceIssue, Long] = {
    personsColl
      .size()
      .mapError(err => ServiceDatabaseIssue(s"Couldn't count persons : $err"))
  }

  def personGet(personId: PersonId): IO[ServiceIssue, Option[Person]] = {
    personsColl
      .fetch(personId)
      .map(_.map(_.transformInto[Person]))
      .mapError(err => ServiceDatabaseIssue(s"Couldn't fetch person : $err"))
  }

  def personExists(personId: PersonId): IO[ServiceIssue, Boolean] = {
    personsColl
      .contains(personId)
      .mapError(err => ServiceDatabaseIssue(s"Couldn't check if person exists : $err"))
  }

  def personDelete(personId: PersonId): IO[ServiceIssue, Unit] = {
    personsColl
      .delete(personId)
      .mapError(err => ServiceDatabaseIssue(s"Couldn't delete person : $err"))
      .unit
  }

  def personCreate(
    firstName: FirstName,
    lastName: LastName,
    birthDate: Option[BirthDate],
    description: Option[PersonDescription]
  ): IO[ServiceIssue, Person] = {
    for {
      personId <- ZIO
                    .attempt(PersonId(ULID.newULID))
                    .mapError(err => ServiceInternalIssue(s"Couldn't generate person id : $err"))
      person    = Person(personId, firstName = firstName, lastName = lastName, birthDate = birthDate, description = description, chosenFaceId = None)
      _        <- personsColl
                    .upsertOverwrite(personId, person.into[DaoPerson].transform)
                    .mapError(err => ServiceDatabaseIssue(s"Couldn't create person : $err"))
    } yield person
  }

  def personUpdate(
    personId: PersonId,
    firstName: FirstName,
    lastName: LastName,
    birthDate: Option[BirthDate],
    description: Option[PersonDescription],
    chosenFaceId: Option[FaceId]
  ): IO[ServiceIssue, Option[Person]] = {
    for {
      maybeDaoPerson <- personsColl
                          .update(
                            personId,
                            _.copy(
                              firstName = firstName,
                              lastName = lastName,
                              birthDate = birthDate,
                              description = description,
                              chosenFaceId = chosenFaceId
                            )
                          )
                          .mapError(err => ServiceDatabaseIssue(s"Couldn't update owner : $err"))
    } yield maybeDaoPerson.map(_.transformInto[Person])
  }

  // -------------------------------------------------------------------------------------------------------------------
  def daoClassificationsToClassifications(input: DaoOriginalClassifications): IO[ServiceIssue, OriginalClassifications] = {
    for {
      original <- originalGet(input.originalId).someOrFail(ServiceDatabaseIssue(s"Couldn't find original : ${input.originalId}"))
      result    = input
                    .into[OriginalClassifications]
                    .withFieldConst(_.original, original)
                    .transform
    } yield result
  }

  def computeClassifications(originalId: OriginalId): IO[ServiceIssue, OriginalClassifications] = {
    val logic = for {
      original  <- originalGet(originalId).someOrFail(ServiceDatabaseIssue(s"Couldn't find original : $originalId"))
      processor <- classificationProcessorEffect
                     .mapError(err => ServiceInternalIssue(s"Unable to get original classifications processor: $err"))
      computed  <- processor
                     .classify(original)
                     .mapError(err => ServiceInternalIssue(s"Unable to extract original classifications : $err"))
      _         <- classificationsColl
                     .upsertOverwrite(originalId, computed.into[DaoOriginalClassifications].transform)
                     .mapError(err => ServiceDatabaseIssue(s"Unable to store computed classifications : $err"))
    } yield computed
    logic.uninterruptible
  }

  override def originalClassifications(originalId: OriginalId): IO[ServiceIssue, Option[OriginalClassifications]] = {
    for {
      stored <- classificationsColl
                  .fetch(originalId)
                  .flatMap(mayBeFound => ZIO.foreach(mayBeFound)(daoClassificationsToClassifications))
                  .mapError(err => ServiceDatabaseIssue(s"Unable to fetch classification from database: $err"))
      result <- computeClassifications(originalId).when(stored.isEmpty)
    } yield stored.orElse(result)
  }

  // -------------------------------------------------------------------------------------------------------------------

  def daoFacesToFaces(input: DaoOriginalFaces): IO[ServiceIssue, OriginalFaces] = {
    for {
      original <- originalGet(input.originalId).someOrFail(ServiceDatabaseIssue(s"Couldn't find original : ${input.originalId}"))
      faces    <- ZIO.foreach(input.facesIds)(faceId => faceGet(faceId))
      result    = input
                    .into[OriginalFaces]
                    .withFieldConst(_.original, original)
                    .withFieldConst(_.faces, faces.flatten)
                    .transform
    } yield result
  }

  def computeFaces(originalId: OriginalId): IO[ServiceIssue, OriginalFaces] = {
    // TODO transaction required
    val logic = for {
      original  <- originalGet(originalId).someOrFail(ServiceDatabaseIssue(s"Couldn't find original : $originalId"))
      processor <- facesProcessorEffect
                     .mapError(err => ServiceInternalIssue(s"Unable to get original detected faces processor : $err"))
      computed  <- processor
                     .extractFaces(original)
                     .mapError(err => ServiceInternalIssue(s"Unable to extract original detected faces : $err"))
      _         <- originalFoundFacesColl
                     .upsertOverwrite(originalId, computed.into[DaoOriginalFaces].transform)
                     .mapError(err => ServiceDatabaseIssue(s"Unable to store computed faces : $err"))
      _         <- ZIO.foreachDiscard(computed.faces)(face =>
                     detectedFaceColl
                       .upsertOverwrite(face.faceId, face.into[DaoDetectedFace].transform)
                       .mapError(err => ServiceDatabaseIssue(s"Unable to store computed detected face : $err"))
                   )
    } yield computed
    logic.uninterruptible
  }

  override def originalFaces(originalId: OriginalId): IO[ServiceIssue, Option[OriginalFaces]] = {
    for {
      stored <- originalFoundFacesColl
                  .fetch(originalId)
                  .flatMap(mayBeFound => ZIO.foreach(mayBeFound)(daoFacesToFaces))
                  .mapError(err => ServiceDatabaseIssue(s"Unable to fetch faces from database: $err"))
      // .tap(stored => Console.printLine(s"Stored : $stored").orDie)
      result <- computeFaces(originalId).when(stored.isEmpty)
    } yield stored.orElse(result)
  }

  // -------------------------------------------------------------------------------------------------------------------

  def daoDetectedObjectsToDetectedObjects(input: DaoOriginalDetectedObjects): IO[ServiceIssue, OriginalDetectedObjects] = {
    for {
      original <- originalGet(input.originalId).someOrFail(ServiceDatabaseIssue(s"Couldn't find original : ${input.originalId}"))
      result    = input
                    .into[OriginalDetectedObjects]
                    .withFieldConst(_.original, original)
                    .transform
    } yield result
  }

  def computedDetectedObjects(originalId: OriginalId): IO[ServiceIssue, OriginalDetectedObjects] = {
    val logic = for {
      original  <- originalGet(originalId).someOrFail(ServiceDatabaseIssue(s"Couldn't find original : $originalId"))
      processor <- objectsProcessorEffect
                     .mapError(err => ServiceInternalIssue(s"Unable to get original detected objects processor : $err"))
      computed  <- processor
                     .extractObjects(original)
                     .mapError(err => ServiceInternalIssue(s"Unable to extract original detected objects : $err"))
      _         <- objectsColl
                     .upsertOverwrite(originalId, computed.into[DaoOriginalDetectedObjects].transform)
                     .mapError(err => ServiceDatabaseIssue(s"Unable to store computed detected objects : $err"))
    } yield computed
    logic.uninterruptible
  }

  override def originalObjects(originalId: OriginalId): IO[ServiceIssue, Option[OriginalDetectedObjects]] = {
    for {
      stored <- objectsColl
                  .fetch(originalId)
                  .flatMap(mayBeFound => ZIO.foreach(mayBeFound)(daoDetectedObjectsToDetectedObjects))
                  .mapError(err => ServiceDatabaseIssue(s"Unable to fetch objects from database: $err"))
      result <- computedDetectedObjects(originalId).when(stored.isEmpty)
    } yield stored.orElse(result)
  }

  // -------------------------------------------------------------------------------------------------------------------

  def daoNormalizedToNormalized(input: DaoOriginalNormalized): IO[ServiceIssue, OriginalNormalized] = {
    for {
      original <- originalGet(input.originalId).someOrFail(ServiceDatabaseIssue(s"Couldn't find original : ${input.originalId}"))
      result    = input
                    .into[OriginalNormalized]
                    .withFieldConst(_.original, original)
                    .transform
    } yield result
  }

  def computeNormalized(originalId: OriginalId): IO[ServiceIssue, OriginalNormalized] = {
    for {
      original <- originalGet(originalId).someOrFail(ServiceDatabaseIssue(s"Couldn't find original : $originalId"))
      computed <- NormalizeProcessor
                    .normalize(original)
                    .mapError(err => ServiceInternalIssue(s"Unable to normalize original : $err"))
      _        <- normalizedColl
                    .upsertOverwrite(originalId, computed.into[DaoOriginalNormalized].transform)
                    .mapError(err => ServiceDatabaseIssue(s"Unable to store computed normalized original : $err"))
    } yield computed
  }

  override def originalNormalized(originalId: OriginalId): IO[ServiceIssue, Option[OriginalNormalized]] = {
    for {
      stored <- normalizedColl
                  .fetch(originalId)
                  .flatMap(mayBeFound => ZIO.foreach(mayBeFound)(daoNormalizedToNormalized))
                  .mapError(err => ServiceDatabaseIssue(s"Unable to fetch normalized original from database: $err"))
      result <- computeNormalized(originalId).when(stored.isEmpty)
    } yield stored.orElse(result)
  }

  // -------------------------------------------------------------------------------------------------------------------
  def daoMiniaturesToMiniatures(input: DaoOriginalMiniatures): IO[ServiceIssue, OriginalMiniatures] = {
    for {
      original <- originalGet(input.originalId).someOrFail(ServiceDatabaseIssue(s"Couldn't find original : ${input.originalId}"))
      result    = input
                    .into[OriginalMiniatures]
                    .withFieldConst(_.original, original)
                    .transform
    } yield result
  }

  def computeMiniatures(originalId: OriginalId): IO[ServiceIssue, OriginalMiniatures] = {
    for {
      original <- originalGet(originalId).someOrFail(ServiceDatabaseIssue(s"Couldn't find original : $originalId"))
      computed <- MiniaturizeProcessor
                    .miniaturize(original)
                    .mapError(err => ServiceInternalIssue(s"Unable to find original miniatures : $err"))
      _        <- miniaturesColl
                    .upsertOverwrite(originalId, computed.into[DaoOriginalMiniatures].transform)
                    .mapError(err => ServiceDatabaseIssue(s"Unable to store computed miniatures : $err"))
    } yield computed
  }

  override def originalMiniatures(originalId: OriginalId): IO[ServiceIssue, Option[OriginalMiniatures]] = {
    for {
      stored <- miniaturesColl
                  .fetch(originalId)
                  .flatMap(mayBeFound => ZIO.foreach(mayBeFound)(daoMiniaturesToMiniatures))
                  .mapError(err => ServiceDatabaseIssue(s"Unable to fetch normalized original from database: $err"))
      result <- computeMiniatures(originalId).when(stored.isEmpty)
    } yield stored.orElse(result)
  }

  override def originalFacesUpdate(originalId: OriginalId, facesIds: List[FaceId]): IO[ServiceIssue, Unit] = {
    for {
      originalFacesDao <- originalFoundFacesColl
                            .update(originalId, previous => previous.copy(facesIds = facesIds))
                            .mapError(err => ServiceDatabaseIssue(s"Unable to update computed faces : $err"))
    } yield ()
  }

  // -------------------------------------------------------------------------------------------------------------------
  def daoOriginal2Original(daoOriginal: DaoOriginal): IO[ServiceIssue, Original] = {
    for {
      store   <- storeGet(daoOriginal.storeId).someOrFail(ServiceDatabaseIssue(s"Couldn't find store for original : ${daoOriginal.storeId}"))
      original = daoOriginal.into[Original].withFieldConst(_.store, store).transform
    } yield original
  }

  override def originalList(): Stream[ServiceStreamIssue, Original] = {
    originalsColl
      .stream()
      .mapZIO(daoOriginal2Original)
      .mapError(err => ServiceStreamInternalIssue(s"Couldn't collect originals : $err"))
  }

  override def originalCount(): IO[ServiceIssue, Long] = for {
    count <- originalsColl
               .size()
               .mapError(err => ServiceDatabaseIssue(s"Couldn't count originals : $err"))
  } yield count

  override def originalGet(originalId: OriginalId): IO[ServiceIssue, Option[Original]] = for {
    maybeDaoOriginal <- originalsColl.fetch(originalId).mapError(err => ServiceDatabaseIssue(s"Couldn't fetch original : $err"))
    maybeOriginal    <- ZIO.foreach(maybeDaoOriginal)(daoOriginal2Original)
  } yield maybeOriginal

  override def originalExists(originalId: OriginalId): IO[ServiceIssue, Boolean] =
    originalsColl.contains(originalId).mapError(err => ServiceDatabaseIssue(s"Couldn't lookup original : $err"))

  override def originalDelete(originalId: OriginalId): IO[ServiceIssue, Unit] = {
    originalsColl
      .delete(originalId)
      .mapError(err => ServiceDatabaseIssue(s"Couldn't delete original : $err"))
      .unit
  }

  override def originalUpsert(providedOriginal: Original): IO[ServiceIssue, Original] = {
    originalsColl
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
      event            = daoEvent
                           .into[Event]
                           .withFieldConst(_.attachment, maybeAttachment)
                           .withFieldComputed(_.publishedOn, in => in.publishedOn.flatMap(uri => Try(java.net.URI(uri).toURL).toOption))
                           .transform
    } yield event
  }

  override def eventList(): Stream[ServiceStreamIssue, Event] = {
    eventsColl
      .stream()
      .mapZIO(daoEvent2Event)
      .mapError(err => ServiceStreamInternalIssue(s"Couldn't collect events : $err"))
  }

  override def eventGet(eventId: EventId): IO[ServiceIssue, Option[Event]] = for {
    maybeDaoEvent <- eventsColl.fetch(eventId).mapError(err => ServiceDatabaseIssue(s"Couldn't fetch event : $err"))
    maybeEvent    <- ZIO.foreach(maybeDaoEvent)(daoEvent2Event)
  } yield maybeEvent

  override def eventDelete(eventId: EventId): IO[ServiceIssue, Unit] = {
    eventsColl
      .delete(eventId)
      .mapError(err => ServiceDatabaseIssue(s"Couldn't delete event : $err"))
      .unit
  }

  override def eventCreate(
    attachment: Option[EventAttachment],
    name: EventName,
    description: Option[EventDescription],
    keywords: Set[Keyword],
    location: Option[Location],
    timestamp: Option[ShootDateTime],
    originalId: Option[OriginalId]
  ): IO[ServiceIssue, Event] = {
    for {
      eventId <- Random.nextUUID.map(EventId.apply)
      event    = Event(eventId, attachment, name, description, location, timestamp, originalId, None, keywords)
      _       <- eventsColl
                   .upsert(eventId, _ => event.into[DaoEvent].transform)
                   .mapError(err => ServiceDatabaseIssue(s"Couldn't create event : $err"))
    } yield event
  }

  override def eventUpdate(
    eventId: EventId,
    name: EventName,
    description: Option[EventDescription],
    location: Option[Location],
    timestamp: Option[ShootDateTime],
    coverOriginalId: Option[OriginalId],
    publishedOn: Option[URL],
    keywords: Set[Keyword]
  ): IO[ServiceIssue, Option[Event]] = {
    for {
      maybeDaoEvent <- eventsColl
                         .update(
                           eventId,
                           _.copy(
                             name = name,
                             description = description,
                             location = location.transformInto[Option[DaoLocation]],
                             timestamp = timestamp,
                             originalId = coverOriginalId,
                             publishedOn = publishedOn.map(_.toString),
                             keywords = keywords
                           )
                         )
                         .mapError(err => ServiceDatabaseIssue(s"Couldn't update owner : $err"))
      event         <- ZIO.foreach(maybeDaoEvent)(daoEvent2Event)
    } yield event

  }

  // -------------------------------------------------------------------------------------------------------------------

  override def ownerList(): Stream[ServiceIssue, Owner] = {
    ownersColl
      .stream()
      .mapBoth(err => ServiceDatabaseIssue(s"Couldn't collect owners : $err"), daoOwner => daoOwner.transformInto[Owner])
  }

  override def ownerGet(ownerId: OwnerId): IO[ServiceIssue, Option[Owner]] = {
    ownersColl
      .fetch(ownerId)
      .mapBoth(err => ServiceDatabaseIssue(s"Couldn't fetch owner : $err"), maybeDaoOwner => maybeDaoOwner.map(_.transformInto[Owner]))
  }

  override def ownerDelete(ownerId: OwnerId): IO[ServiceIssue, Unit] = {
    ownersColl
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
      owner    = Owner(ownerId, firstName, lastName, birthDate, None)
      _       <- ownersColl
                   .upsert(owner.id, _ => owner.transformInto[DaoOwner])
                   .mapError(err => ServiceDatabaseIssue(s"Couldn't create owner : $err"))
    } yield owner
  }

  override def ownerUpdate(
    ownerId: OwnerId,
    firstName: FirstName,
    lastName: LastName,
    birthDate: Option[BirthDate],
    coverOriginalId: Option[OriginalId]
  ): IO[ServiceIssue, Option[Owner]] = {
    for {
      maybeDaoOwner <- ownersColl
                         .update(
                           ownerId,
                           _.copy(
                             firstName = firstName,
                             lastName = lastName,
                             birthDate = birthDate,
                             originalId = coverOriginalId
                           )
                         )
                         .mapError(err => ServiceDatabaseIssue(s"Couldn't update owner : $err"))
      maybeOwner     = maybeDaoOwner.map(_.transformInto[Owner])
    } yield maybeOwner
  }

  // -------------------------------------------------------------------------------------------------------------------

  override def storeList(): Stream[ServiceIssue, Store] = {
    storesColl
      .stream()
      .map(daoStore => daoStore.transformInto[Store])
      .mapError(err => ServiceDatabaseIssue(s"Couldn't collect stores : $err"))
  }

  override def storeGet(storeId: StoreId): IO[ServiceIssue, Option[Store]] = {
    storesColl
      .fetch(storeId)
      .mapBoth(err => ServiceDatabaseIssue(s"Couldn't fetch store : $err"), maybeDaoStore => maybeDaoStore.map(_.transformInto[Store]))
  }

  override def storeDelete(storeId: StoreId): IO[ServiceIssue, Unit] = {
    storesColl
      .delete(storeId)
      .mapError(err => ServiceDatabaseIssue(s"Couldn't delete store : $err"))
      .unit
  }

  override def storeCreate(
    providedStoreId: Option[StoreId],
    name: Option[StoreName],
    ownerId: OwnerId,
    baseDirectory: BaseDirectoryPath,
    includeMask: Option[IncludeMask],
    ignoreMask: Option[IgnoreMask]
  ): IO[ServiceIssue, Store] = {
    for {
      storeId <- ZIO
                   .from(providedStoreId)
                   .orElse(ZIO.attempt(StoreId(UUID.randomUUID())))
                   .mapError(err => ServiceInternalIssue(s"Unable to create a store identifier : $err"))
      store    = Store(storeId, name, ownerId, baseDirectory, includeMask, ignoreMask)
      _       <- storesColl
                   .upsert(store.id, _ => store.transformInto[DaoStore])
                   .mapError(err => ServiceDatabaseIssue(s"Couldn't create store : $err"))
    } yield store
  }

  override def storeUpdate(
    storeId: StoreId,
    name: Option[StoreName],
    baseDirectory: BaseDirectoryPath,
    includeMask: Option[IncludeMask],
    ignoreMask: Option[IgnoreMask]
  ): IO[ServiceIssue, Option[Store]] = {
    for {
      maybeDaoStore <- storesColl
                         .update(
                           storeId,
                           _.copy(
                             name = name,
                             baseDirectory = baseDirectory,
                             includeMask = includeMask,
                             ignoreMask = ignoreMask
                           )
                         )
                         .mapError(err => ServiceDatabaseIssue(s"Couldn't update store : $err"))
      maybeStore     = maybeDaoStore.map(_.transformInto[Store])
    } yield maybeStore
  }

  // -------------------------------------------------------------------------------------------------------------------

  private def synchronizeOriginal(original: Original): IO[ServiceIssue, Original] = {
    val logic = for {
      available <- originalExists(original.id)
      _         <- originalUpsert(original).when(!available)
    } yield original
    logic @@ annotated("originalId" -> original.id.toString, "originalMediaPath" -> original.absoluteMediaPath.toString)
  }

  private def getEventForAttachment(attachment: EventAttachment): IO[ServiceIssue, Option[Event]] = {
    // TODO first basic and naive implementation - not good for complexity
    eventsColl
      .collect(valueFilter = daoFilter => daoFilter.attachment.exists(thatAttachment => thatAttachment.storeId == attachment.store.id && thatAttachment.eventMediaDirectory == attachment.eventMediaDirectory))
      .mapBoth(err => ServiceDatabaseIssue(s"Couldn't collect events : $err"), _.headOption)
      .flatMap(mayBeDaoEvent => ZIO.foreach(mayBeDaoEvent)(daoEvent2Event))
  }

  private def createDefaultEvent(original: Original, attachment: EventAttachment): IO[ServiceIssue, Event] = {
    // TODO add automatic keywords extraction
    keywordSentenceToKeywords(attachment.store.id, attachment.eventMediaDirectory.toString).flatMap { autoKeywords =>
      eventCreate(
        attachment = Some(attachment),
        name = EventName(attachment.eventMediaDirectory.toString),
        description = None,
        keywords = autoKeywords,
        location = original.location,
        timestamp = original.cameraShootDateTime,
        originalId = Some(original.id)
      )
    }
  }

  private def synchronizeState(original: Original): IO[ServiceIssue, (original: Original, state: State)] = {
    val relatedEventAttachment = MediaBuilder.buildEventAttachment(original)
    val logic                  = for {
      mayBeEvent   <- ZIO.foreach(relatedEventAttachment)(getEventForAttachment).map(_.flatten)
      currentState <- stateGet(original.id)
      now          <- Clock.currentDateTime
      relativePath  = original.mediaPath.path
      absolutePath  = original.store.baseDirectory.path.resolve(relativePath)
      updatedState  = currentState
                        .map(state =>
                          state.copy(
                            originalLastChecked = LastChecked(now),
                            originalHash = state.originalHash.orElse(
                              HashOperations
                                .fileDigest(absolutePath)
                                .toOption
                                .map(OriginalHash.apply)
                            )
                          )
                        )
                        .getOrElse(
                          State(
                            originalId = original.id,
                            originalHash = HashOperations.fileDigest(absolutePath).toOption.map(OriginalHash.apply),
                            originalAddedOn = AddedOn(now),
                            originalLastChecked = LastChecked(now),
                            mediaAccessKey = MediaBuilder.buildDefaultMediaAccessKey(original, mayBeEvent),
                            mediaLastSynchronized = None
                          )
                        )
      state        <- stateUpsert(original.id, updatedState)
    } yield (original, state)
    logic @@ annotated("originalId" -> original.id.toString, "originalMediaPath" -> original.absoluteMediaPath.toString)
  }

  private def synchronizeMedia(input: (original: Original, state: State)): IO[ServiceIssue, (media: Media, state: State)] = {
    val relatedEventAttachment = MediaBuilder.buildEventAttachment(input.original)
    val logic                  = for {
      mayBeEvent   <- ZIO
                        .foreach(relatedEventAttachment)(attachment =>
                          getEventForAttachment(attachment)
                            .someOrElseZIO(createDefaultEvent(input.original, attachment))
                        )
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
                            userDefinedLocation = None,
                            deductedLocation = None
                          )
                          mediasColl
                            .upsert(input.state.mediaAccessKey, _ => daoMedia)
                            .flatMap(daoMedia2Media)
                            .mapError(err => ServiceDatabaseIssue(s"Couldn't create media : $err"))
                        }
    } yield (currentMedia, input.state)
    logic @@ annotated("originalId" -> input.original.id.toString, "originalMediaPath" -> input.original.absoluteMediaPath.toString)
  }

  private def synchronizeProcessors(input: (media: Media, state: State)): IO[ServiceIssue, (media: Media, state: State)] = {
    val logic = for {
      _                         <- originalNormalized(input.media.original.id)                   // required to optimize AI work so not launched in background
      fiberMiniaturesFiber      <- originalMiniatures(input.media.original.id)                   // .fork
      fiberFacesFiber           <- originalFaces(input.media.original.id).ignoreLogged           // .fork
      fiberClassificationsFiber <- originalClassifications(input.media.original.id).ignoreLogged // .fork
      fiberObjectsFiber         <- originalObjects(input.media.original.id).ignoreLogged         // .fork
      // TODO investigate why this is not working
      // _                         <- fiberMiniaturesFiber.join
      // _                         <- fiberFacesFiber.join
      // _                         <- fiberClassificationsFiber.join
      // _                         <- fiberObjectsFiber.join
    } yield input

    // TODO AI processors may fail, but we don't want to stop the whole synchronization => check the added `.ignoreLogged`
    logic @@ annotated("originalId" -> input.media.original.id.toString, "originalMediaPath" -> input.media.original.absoluteMediaPath.toString)
  }

  // TODO generic utility function
  def zstreamGenerator[R, E, A](first: ZIO[R, E, Option[A]])(next: A => ZIO[R, E, Option[A]]): ZStream[R, E, A] =
    ZStream.fromZIO(first).flatMap {
      case None        => ZStream.empty
      case Some(start) => ZStream.paginateZIO(start)(a => next(a).map(n => (a, n)))
    }

  // may need several executions to fully be able to induce locations
  // TODO of course too slow (but simpler than keeping a buffer window : for all 114795 photos, 17m30s with induction 10m41s without )
  private def locationInduction(input: (media: Media, state: State)): IO[ServiceIssue, (media: Media, state: State)] = {
    if (input.media.original.hasLocation || input.media.deductedLocation.isDefined) ZIO.succeed(input)
    else {
      def acceptable(current: Media): Boolean = {
        val sameUser       = current.original.store.ownerId.asULID == input.media.original.store.ownerId
        val elapsedSeconds = Math.abs(current.timestamp.toEpochSecond - input.media.timestamp.toEpochSecond)
        (elapsedSeconds < 3 * 3600) && sameUser
      }

      def prevCandidates =
        zstreamGenerator(mediaPrevious(input.media.accessKey))(prev => mediaPrevious(prev.accessKey))
          .takeWhile(acceptable)
          .filter(_.original.hasLocation)

      def nextCandidates =
        zstreamGenerator(mediaNext(input.media.accessKey))(next => mediaNext(next.accessKey))
          .takeWhile(acceptable)
          .filter(_.original.hasLocation)

      for {
        firstPrev                <- prevCandidates.runHead
        firstNext                <- nextCandidates.runHead
        validDistance             = firstPrev
                                      .flatMap(_.original.location)
                                      .flatMap(fp => firstNext.flatMap(_.original.location).map(fn => fp.distanceTo(fn)))
                                      .exists(_ < 750) // meters // TODO add config parameter
        inductedLocationInMiddle  = if (validDistance)
                                      firstPrev.flatMap(_.original.location)
                                    else None
        inductedLocationFirstShot = if (
                                      firstPrev.isEmpty
                                      && firstNext.isDefined
                                      && firstNext.exists(fn => fn.timestamp.toEpochSecond - input.media.timestamp.toEpochSecond < 30 * 60) // 30 minutes // TODO add config parameter
                                    )
                                      firstNext.flatMap(_.original.location)
                                    else None
        inductedLocation          = inductedLocationFirstShot.orElse(inductedLocationInMiddle)
        updatedMedia              = input.media.copy(deductedLocation = inductedLocation)
        _                        <- mediaUpdate(input.media.accessKey, updatedMedia).when(inductedLocation.isDefined)
      } yield (updatedMedia, input.state)
    }
  }

  private def synchronizeSearchEngine(inputs: Chunk[(media: Media, state: State)]): IO[ServiceIssue, Chunk[MediaBag]] = {
    val logic = for {
      now       <- Clock.currentDateTime.map(LastSynchronized.apply)
      bag       <- ZIO.foreach(inputs) { input =>
                     for {
                       classifications <- originalClassifications(input.media.original.id)
                       objects         <- originalObjects(input.media.original.id)
                       miniatures      <- originalMiniatures(input.media.original.id)
                       faces           <- originalFaces(input.media.original.id)
                       normalized      <- originalNormalized(input.media.original.id)
                     } yield MediaBag(
                       media = input.media,
                       state = input.state,
                       processedClassifications = classifications,
                       processedObjects = objects,
                       processedFaces = faces,
                       processedMiniatures = miniatures,
                       processedNormalized = normalized
                     )
                   }
      published <- search
                     .publish(bag)
                     .mapError(err => ServiceInternalIssue(s"Unable to publish media to search engine : $err"))
      _         <- ZIO.foreach(inputs)(input => stateUpsert(input.media.original.id, input.state.copy(mediaLastSynchronized = Some(now))))
    } yield bag // TODO no transaction take care
    logic
  }

  override def synchronizeStart(addedThoseLastDays: Option[Int]): IO[ServiceIssue, Unit] = {
    val finishedLogic =
      for {
        currentDate <- Clock.currentDateTime
        _           <- synchronizeStatusRef
                         .update(status =>
                           status.copy(
                             running = false,
                             lastUpdated = Some(currentDate),
                             startedAt = None
                           )
                         )
        _           <- synchronizeFiberRef
                         .update(_ => None)
      } yield ()

    val syncLogic = {
      for {
        _              <- ZIO.log(s"Synchronization started addedThoseLastDays=$addedThoseLastDays")
        stores         <- storeList().runCollect
        serviceConfig  <- ServiceConfig.config
                            .mapError(err => ServiceInternalIssue(s"Unable to retrieve service configuration : $err"))
        searchConfig    = serviceConfig.fileSystemSearch.toCoreConfig
        originalsStream = ZStream
                            .from(stores)
                            .mapZIO(store => ZIO.attemptBlocking(FileSystemSearch.originalsStreamFromSearchRoot(store, searchConfig)))
                            .absolve
                            .flatMap(javaStream => ZStream.fromJavaStream(javaStream))
                            .right
        _              <- originalsStream
                            .tap(_ => updateSynchronizeCheckedStatus())
                            .filter(original => addedThoseLastDays.isEmpty || original.fileLastModified.offsetDateTime.isAfter(OffsetDateTime.now().minusDays(addedThoseLastDays.get)))
                            .mapZIO(synchronizeOriginal)
                            .mapZIO(synchronizeState)
                            .mapZIO(synchronizeMedia)
                            .filter(_.state.mediaLastSynchronized.isEmpty)
                            .mapZIO(input => synchronizeProcessors(input).uninterruptible)
                            .mapZIO(input => locationInduction(input).uninterruptible)
                            .grouped(50)
                            .mapZIO(input => synchronizeSearchEngine(input).uninterruptible)
                            .mapZIO(input => updateSynchronizeProcessedStatus(input).uninterruptible)
                            .runDrain
                            .mapError(err => ServiceInternalIssue(s"Unable to synchronize : $err"))
                            .catchAll(e => ZIO.logError(s"Sync failed: $e"))
                            .tap(_ => ZIO.log("Synchronization finished !"))
                            .tap(_ => finishedLogic) // TODO not sure it's the best place to do this
      } yield ()
    }

    // TODO temporary quick & dirty implementation

    val startLogic = for {
      currentDate <- Clock.currentDateTime
      _           <- synchronizeStatusRef
                       .update(status =>
                         status.copy(
                           running = true,
                           lastUpdated = Some(currentDate),
                           startedAt = Some(currentDate),
                           checkedCount = 0,
                           processedCount = 0
                         )
                       )
      fiber       <- syncLogic
                       .tapError(err => ZIO.logError(s"Couldn't synchronize : $err"))
                       .forkDaemon
      _           <- synchronizeFiberRef
                       .update {
                         case None      => Some(fiber)
                         case something => something // already running
                       }
    } yield ()

    for { // TODO need refactoring - temporary unsatisfying implementation
      current <- synchronizeStatusRef.get
      _       <- startLogic.when(!current.running)
    } yield ()
  }

  override def synchronizeWait(): IO[ServiceIssue, Unit] = {
    for { // TODO need refactoring - temporary unsatisfying implementation
      fiber <- synchronizeFiberRef.get
      _     <- ZIO.foreachDiscard(fiber)(f => f.join)
    } yield ()

  }

  override def synchronizeStop(): IO[ServiceIssue, Unit] = {
    for { // TODO need refactoring - temporary unsatisfying implementation
      fiber <- synchronizeFiberRef.get
      _     <- ZIO.foreachDiscard(fiber)(f => f.interrupt)
      _     <- ZIO.foreachDiscard(fiber)(f => f.join)
    } yield ()
  }

  override def synchronizeStatus(): IO[ServiceIssue, SynchronizeStatus] = {
    synchronizeStatusRef.get
  }

  def updateSynchronizeProcessedStatus(input: Chunk[MediaBag]): UIO[Chunk[MediaBag]] = {
    for {
      currentDate <- Clock.currentDateTime
      _           <- synchronizeStatusRef
                       .update(status =>
                         status.copy(
                           lastUpdated = Some(currentDate),
                           processedCount = status.processedCount + input.size
                         )
                       )
    } yield input
  }

  def updateSynchronizeCheckedStatus(): UIO[Unit] = {
    for {
      currentDate <- Clock.currentDateTime
      _           <- synchronizeStatusRef
                       .update(status =>
                         status.copy(
                           checkedCount = status.checkedCount + 1
                         )
                       )
    } yield ()
  }

  // -------------------------------------------------------------------------------------------------------------------

  private def camelTokenize(that: String): Array[String] = that.split("(?=[A-Z][^A-Z])|(?:(?<=[^A-Z])(?=[A-Z]+))")

  private def camelToKebabCase(that: String): String = camelTokenize(that).map(_.toLowerCase).mkString("-")

  @tailrec
  private def keywordApplyRewritings(input: String, rewritings: List[Rewriting]): String = {
    rewritings match {
      case Nil                                               => input
      // TODO rewriting.regex is not safe
      case (rewriting @ Rewriting(_, replacement)) :: remain => keywordApplyRewritings(rewriting.pattern.replaceAllIn(input, replacement), remain)
    }
  }

  def extractKeywords(sentence: String, rules: Option[KeywordRules]): Set[String] = {
    keywordApplyRewritings(sentence, rules.map(_.rewritings).getOrElse(Nil))
      .split("[- /,']+")
      .toList
      .filter(_.nonEmpty)
      // .filterNot(_.contains("'"))
      .flatMap(key => camelToKebabCase(key).split("-")) // TODO add dedicated option to rules ?
      .map(token => rules.flatMap(_.mappings.find(_.from == token.toLowerCase).map(_.to)).getOrElse(token))
      .flatMap(_.split("[- ]+"))
      .filter(_.trim.nonEmpty)
      .filterNot(_.matches("^[-0-9]+$")) // TODO add option to rules to ignore standalone numbers
      .map(_.toLowerCase)
      .filter(key => rules.isEmpty || !rules.get.ignoring.contains(key))
      .toSet
  }

  override def keywordSentenceToKeywords(storeId: StoreId, sentence: String): IO[ServiceIssue, Set[Keyword]] = {
    for {
      mayBeRules <- keywordRulesGet(storeId)
      keywords    = extractKeywords(sentence, mayBeRules)
      // TODO add automatic keywords for year and month ?
    } yield keywords.map(Keyword.apply)
  }

  override def keywordList(storeId: StoreId): IO[ServiceIssue, Map[Keyword, Int]] = {
    // TODO first implementation - too slow but with low memory usage
    mediaList()
      .filter(_.original.store.id == storeId)
      .map(media => (media.keywords.toList ++ media.events.flatMap(_.keywords)).groupMapReduce(_.text)(_ => 1)(_ + _))
      .runFold(Map.empty[Keyword, Int])((acc, curr) =>
        curr.foldLeft(acc) { case (res, (keyword, count)) =>
          res + (Keyword(keyword) -> (count + res.getOrElse(Keyword(keyword), 0)))
        }
      )
      .mapError(err => ServiceDatabaseIssue(s"Couldn't extract store keywords : $err"))
  }

  override def keywordDelete(storeId: StoreId, keyword: Keyword): IO[ServiceIssue, Unit] = {
    // TODO first implementation - too slow but with low memory usage
    mediaList()
      .filter(_.original.store.id == storeId)
      .map(media => media.copy(keywords = media.keywords.filterNot(_.text == keyword.text)))
      .flatMap(media => ZStream.fromIterable(media.events))
      .map(event => event.copy(keywords = event.keywords.filterNot(_.text == keyword.text)))
      .tap(event =>
        eventUpdate(
          event.id,
          name = event.name,
          description = event.description,
          location = event.location,
          timestamp = event.timestamp,
          coverOriginalId = event.originalId,
          publishedOn = event.publishedOn,
          keywords = event.keywords
        )
      )
      .runDrain
      .mapError(err => ServiceDatabaseIssue(s"Couldn't delete keyword : $err"))
  }

  override def keywordRulesList(): IO[ServiceIssue, Chunk[KeywordRules]] = {
    keywordRulesColl
      .stream()
      .mapZIO(r => ZIO.attempt(r.transformInto[KeywordRules]))
      .mapError(err => ServiceDatabaseIssue(s"Couldn't collect keyword rules : $err"))
      .runCollect
  }

  override def keywordRulesGet(storeId: StoreId): IO[ServiceIssue, Option[KeywordRules]] = {
    keywordRulesColl
      .fetch(storeId)
      .flatMap(r => ZIO.attempt(r.map(_.transformInto[KeywordRules])))
      .mapError(err => ServiceDatabaseIssue(s"Couldn't get keyword rules : $err"))
  }

  override def keywordRulesUpsert(storeId: StoreId, rules: KeywordRules): IO[ServiceIssue, Unit] = {
    keywordRulesColl
      .upsert(storeId, _ => rules.transformInto[DaoKeywordRules])
      .mapError(err => ServiceDatabaseIssue(s"Couldn't create or update keyword rules : $err"))
      .unit
  }

  override def keywordRulesDelete(storeId: StoreId): IO[ServiceIssue, Unit] = {
    keywordRulesColl
      .delete(storeId)
      .mapError(err => ServiceDatabaseIssue(s"Couldn't delete keyword rule : $err"))
      .unit
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
  given LMDBKodec[FaceId] = new LMDBKodec {
    def encode(key: FaceId): Array[Byte]                     = key.asString.getBytes(charset.name())
    def decode(keyBytes: ByteBuffer): Either[String, FaceId] = ulidBytesToEither(keyBytes).map(FaceId.apply)
  }

  // -------------------------------------------------------------------------------------------------------------------
  given LMDBKodec[PersonId] = new LMDBKodec {
    def encode(key: PersonId): Array[Byte]                     = key.asString.getBytes(charset.name())
    def decode(keyBytes: ByteBuffer): Either[String, PersonId] = ulidBytesToEither(keyBytes).map(PersonId.apply)
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
  private val originalsCollectionName       = "originals"
  private val statesCollectionName          = "states"
  private val eventsCollectionName          = "events"
  private val mediasCollectionName          = "medias"
  private val ownersCollectionName          = "owners"
  private val storesCollectionName          = "stores"
  private val keywordRulesCollectionName    = "keywordRules"
  private val classificationsCollectionName = "classifications"
  private val detectedFacesCollectionName   = "detectedFaces"
  private val facesCollectionName           = "faces"
  private val objectsCollectionName         = "objects"
  private val miniaturesCollectionName      = "miniatures"
  private val normalizedCollectionName      = "normalized"
  private val personsCollectionName         = "persons"

  private val allCollections = List(
    originalsCollectionName,
    statesCollectionName,
    eventsCollectionName,
    mediasCollectionName,
    ownersCollectionName,
    storesCollectionName,
    keywordRulesCollectionName,
    classificationsCollectionName,
    detectedFacesCollectionName,
    facesCollectionName,
    objectsCollectionName,
    miniaturesCollectionName,
    normalizedCollectionName,
    personsCollectionName
  )

  def setup(lmdb: LMDB, search: SearchService): IO[LMDBIssues | CoreIssue, MediaService] = for {
    _                          <- ZIO.foreachDiscard(allCollections)(col => lmdb.collectionAllocate(col).ignore)
    originalsColl              <- lmdb.collectionGet[OriginalId, DaoOriginal](originalsCollectionName)
    statesColl                 <- lmdb.collectionGet[OriginalId, DaoState](statesCollectionName)
    eventsColl                 <- lmdb.collectionGet[EventId, DaoEvent](eventsCollectionName)
    mediasColl                 <- lmdb.collectionGet[MediaAccessKey, DaoMedia](mediasCollectionName)
    ownersColl                 <- lmdb.collectionGet[OwnerId, DaoOwner](ownersCollectionName)
    storesColl                 <- lmdb.collectionGet[StoreId, DaoStore](storesCollectionName)
    keywordRulesColl           <- lmdb.collectionGet[StoreId, DaoKeywordRules](keywordRulesCollectionName)
    classificationsColl        <- lmdb.collectionGet[OriginalId, DaoOriginalClassifications](classificationsCollectionName)
    detectedFacesColl          <- lmdb.collectionGet[FaceId, DaoDetectedFace](detectedFacesCollectionName)
    originalFoundFacesColl     <- lmdb.collectionGet[OriginalId, DaoOriginalFaces](facesCollectionName)
    objectsColl                <- lmdb.collectionGet[OriginalId, DaoOriginalDetectedObjects](objectsCollectionName)
    miniaturesColl             <- lmdb.collectionGet[OriginalId, DaoOriginalMiniatures](miniaturesCollectionName)
    normalizedColl             <- lmdb.collectionGet[OriginalId, DaoOriginalNormalized](normalizedCollectionName)
    personsColl                <- lmdb.collectionGet[PersonId, DaoPerson](personsCollectionName)
    classificationProcessor    <- ClassificationProcessor.allocate().memoize
    facesProcessor             <- FacesProcessor.allocate().memoize
    objectsProcessor           <- ObjectsDetectionProcessor.allocate().memoize
    synchronizeStatusReference <- Ref.make(SynchronizeStatus.empty)
    synchronizeFiberReference  <- Ref.make(Option.empty[Fiber[Nothing, Unit]])
  } yield new MediaServiceLive(
    lmdb,
    search,
    // ------------------------
    originalsColl,
    statesColl,
    eventsColl,
    mediasColl,
    ownersColl,
    storesColl,
    keywordRulesColl,
    classificationsColl,
    detectedFacesColl,
    originalFoundFacesColl,
    objectsColl,
    miniaturesColl,
    normalizedColl,
    personsColl,
    // ------------------------
    classificationProcessor,
    facesProcessor,
    objectsProcessor,
    // ------------------------
    synchronizeStatusReference,
    synchronizeFiberReference
  )

  // -------------------------------------------------------------------------------------------------------------------

}
