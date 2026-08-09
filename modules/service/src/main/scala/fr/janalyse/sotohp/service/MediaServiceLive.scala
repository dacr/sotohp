package fr.janalyse.sotohp.service

import fr.janalyse.sotohp.core.{CoreIssue, FileSystemSearch, FileSystemSearchCoreConfig, HashOperations, MediaBuilder, OriginalBuilder, SearchFilter}
import fr.janalyse.sotohp.model.{FaceId, PersonId, *}
import fr.janalyse.sotohp.processor.{
  ClassificationIssue,
  ClassificationProcessor,
  FaceFeaturesIssue,
  FaceFeaturesProcessor,
  FacesDetectionIssue,
  FacesProcessor,
  MiniaturizeProcessor,
  NormalizeProcessor,
  ObjectsDetectionIssue,
  ObjectsDetectionProcessor
}
import fr.janalyse.sotohp.processor.model.*
import fr.janalyse.sotohp.search.SearchService
import fr.janalyse.sotohp.search.model.MediaBag
import json.*
import fr.janalyse.sotohp.service.dao.*
import fr.janalyse.sotohp.service.model.*
import fr.janalyse.sotohp.service.model.SynchronizeAction.{Stop, WaitForCompletion}
import wvlet.airframe.ulid.ULID
import zio.*
import zio.lmdb.{GetErrors, IdxKey, IndexErrors, LMDB, LMDBCodec, LMDBCollection, StorageSystemError, StorageUserError}
import zio.lmdb.keycodecs.{KeyCodec, KeyCodecError, KeyTypeId}
import zio.stream.{Stream, ZStream}
import io.scalaland.chimney.dsl.*
import zio.ZIOAspect.annotated

import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.time.{Instant, OffsetDateTime}
import java.util.UUID
import java.util.regex.Pattern
import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}
import zio.lmdb.keycodecs.timestamp.TimestampCodec.given
import zio.lmdb.keycodecs.uuidv7.UUIDv7.given
import zio.lmdb.keycodecs.ulid.ULIDCodec.given
import zio.lmdb.keycodecs.geo.GeoCodec.given
import zio.lmdb.keycodecs.geo.GEOTools

type LMDBIssues = StorageUserError | StorageSystemError | IndexErrors

class MediaServiceLive private (
  lmdb: LMDB,
  search: SearchService,
  collections: MediaServiceDatabase,
  processors: MediaServiceProcessors,
  // ------------------------
  synchronizeStatusRef: Ref[SynchronizeStatus],
  synchronizeFiberRef: Ref[Option[Fiber[ServiceIssue, Unit]]]
) extends MediaService {

  // -------------------------------------------------------------------------------------------------------------------

  private def daoMedia2Media(daoMedia: DaoMedia): IO[ServiceIssue, Media] = {
    for {
      original <- originalGet(daoMedia.originalId).someOrFail(ServiceDatabaseIssue(s"Couldn't find original : ${daoMedia.originalId}"))
      bag      <- ZIO.foreach(daoMedia.bagId)(bagId => bagGet(bagId).some.mapError(err => ServiceDatabaseIssue(s"Couldn't fetch bag : $err")))
      media     = daoMedia
                    .into[Media]
                    .withFieldConst(_.original, original)
                    .withFieldConst(_.bag, bag)
                    .transform
    } yield media
  }

  private def buildMediaAccessKey(media: Media): MediaAccessKey = MediaAccessKey(media.timestamp, media.original.id)

  private def daoMediaToMediaTuple(daoMedia: DaoMedia) = {
    daoMedia2Media(daoMedia)
      .mapError(err => Option(ServiceDatabaseIssue(s"Couldn't convert back stored media: $err")))
      .map(media => buildMediaAccessKey(media) -> media)
  }

  override def mediaList(): Stream[ServiceStreamIssue, MediaTuple] = {
    val medias = for {
      firstKey <- collections.originalIdByTimestamp
                    .head()
                    .map(_.map((key, originalId) => key))
                    .mapError(err => ServiceStreamInternalIssue(s"Couldn't reach first key : $err"))
      stream    = firstKey
                    .map(key => collections.originalIdByTimestamp.indexed(key, limitToKey = false))
                    .getOrElse(ZStream.empty)
                    .mapZIO { case ((timestamp, originalId), _) =>
                      collections.medias
                        .fetch(originalId)
                        .some
                        .flatMap(daoMedia2Media)
                        .map(media => MediaAccessKey(timestamp, originalId) -> media)
                    }
                    .mapError(err => ServiceStreamInternalIssue(s"Couldn't collect medias : $err"))
    } yield stream

    ZStream.unwrapScoped(medias)
  }

  // Walks the GEO index so only medias that actually have a location are
  // touched, and projects each one into the slim MediaLocation shape used
  // by the map tab. Skips the Original+Bags join and EXIF/keywords —
  // ~20x smaller payload than mediaList for the same set of medias.
  override def mediaLocationList(): Stream[ServiceStreamIssue, MediaLocation] = {
    val locations = for {
      firstKey <- collections.originalIdByLocation
                    .head()
                    .map(_.map((key, _) => key))
                    .mapError(err => ServiceStreamInternalIssue(s"Couldn't reach first location key : $err"))
      stream    = firstKey
                    .map(key => collections.originalIdByLocation.indexed(key, limitToKey = false))
                    .getOrElse(ZStream.empty)
                    .mapZIO { case (_, originalId) =>
                      collections.medias
                        .fetch(originalId)
                        .map(_.flatMap(daoMedia =>
                          // location must be defined for entries in this index;
                          // flatMap collapses the safety check with no extra cost
                          daoMedia.location.map(loc =>
                            MediaLocation(
                              accessKey = MediaAccessKey(daoMedia.timestamp.toInstant, originalId),
                              latitude = loc.latitude,
                              longitude = loc.longitude,
                              shootDateTime = daoMedia.shootDateTime,
                              starred = daoMedia.starred,
                              bagId = daoMedia.bagId
                            )
                          )
                        ))
                    }
                    .collect { case Some(loc) => loc }
                    .mapError(err => ServiceStreamInternalIssue(s"Couldn't collect media locations : $err"))
    } yield stream

    ZStream.unwrapScoped(locations)
  }

  override def mediaFind(nearKey: MediaAccessKey): IO[ServiceIssue, Option[MediaTuple]] = {
    // TODO temporary implementation
    mediaNext(nearKey)
      .orElse(mediaPrevious(nearKey))
  }

  override def mediaSearch(keywordsFilter: Set[Keyword]): Stream[ServiceStreamIssue, MediaTuple] = {
    // TODO temporary implementation
    mediaList()
      .filter(mediaTuple => keywordsFilter.forall(mediaTuple.media.allKeywords.contains))
  }

  override def mediaFirst(): IO[ServiceIssue, Option[MediaTuple]] = {
    collections.originalIdByTimestamp
      .head()
      .mapError(err => ServiceDatabaseIssue(s"Couldn't get first media from index: $err"))
      .some
      .flatMap { (_, originalId) =>
        collections.medias
          .fetch(originalId)
          .mapError(err => Option(ServiceDatabaseIssue(s"Couldn't get media: $err")))
          .someOrFail(None)
          .flatMap(daoMediaToMediaTuple)
      }
      .unsome
      .logError("Couldn't get first media")
  }

  override def mediaPrevious(nearKey: MediaAccessKey): IO[ServiceIssue, Option[MediaTuple]] = {
    collections.originalIdByTimestamp
      .previous(nearKey.toNative)
      .mapError(err => ServiceDatabaseIssue(s"Couldn't get previous media from index: $err"))
      .some
      .flatMap { (_, originalId) =>
        collections.medias
          .fetch(originalId)
          .mapError(err => Option(ServiceDatabaseIssue(s"Couldn't get media: $err")))
          .someOrFail(None)
          .flatMap(daoMediaToMediaTuple)
      }
      .unsome
      .logError(s"Couldn't get previous media near $nearKey")
  }

  override def mediaNext(nearKey: MediaAccessKey): IO[ServiceIssue, Option[MediaTuple]] = {
    collections.originalIdByTimestamp
      .next(nearKey.toNative)
      .mapError(err => ServiceDatabaseIssue(s"Couldn't get next media from index: $err"))
      .some
      .flatMap { (_, originalId) =>
        collections.medias
          .fetch(originalId)
          .mapError(err => Option(ServiceDatabaseIssue(s"Couldn't get media: $err")))
          .someOrFail(None)
          .flatMap(daoMediaToMediaTuple)
      }
      .unsome
      .logError(s"Couldn't get next media near $nearKey")
  }

  override def mediaLast(): IO[ServiceIssue, Option[MediaTuple]] = {
    collections.originalIdByTimestamp
      .last()
      .mapError(err => ServiceDatabaseIssue(s"Couldn't get last media from index: $err"))
      .some
      .flatMap { (_, originalId) =>
        collections.medias
          .fetch(originalId)
          .mapError(err => Option(ServiceDatabaseIssue(s"Couldn't get media: $err")))
          .someOrFail(None)
          .flatMap(daoMediaToMediaTuple)
      }
      .unsome
      .logError("Couldn't get last media")
  }

  // Walk the (timestamp, originalId) index from `fromKey` in the requested
  // direction, emitting up to `limit` items as a stream. Collecting the index
  // entries inside a single LMDB read transaction means the per-step cursor
  // open/close stays in the microsecond range — vs. the client doing N HTTP
  // round-trips, each paying request/response/serialization latency.
  override def mediaStream(fromKey: MediaAccessKey, backward: Boolean, limit: Int): Stream[ServiceStreamIssue, MediaTuple] = {
    val safeLimit = math.max(0, limit)
    val collectKeys: IO[ServiceStreamIssue, List[(Instant, OriginalId)]] =
      collections.originalIdByTimestamp
        .readOnly { ops =>
          def loop(currentKey: (Instant, OriginalId), remaining: Int, acc: List[(Instant, OriginalId)]): IO[zio.lmdb.FetchErrors, List[(Instant, OriginalId)]] = {
            if (remaining <= 0) ZIO.succeed(acc.reverse)
            else {
              val step = if (backward) ops.previous(currentKey) else ops.next(currentKey)
              step.flatMap {
                case None              => ZIO.succeed(acc.reverse)
                case Some((newKey, _)) => loop(newKey, remaining - 1, newKey :: acc)
              }
            }
          }
          loop(fromKey.toNative, safeLimit, Nil)
        }
        .mapError(err => ServiceStreamInternalIssue(s"Couldn't walk media timestamp index: $err"))

    ZStream
      .fromIterableZIO(collectKeys)
      .mapZIO { case (timestamp, originalId) =>
        collections.medias
          .fetch(originalId)
          .mapError(err => ServiceStreamInternalIssue(s"Couldn't fetch media: $err"))
          .flatMap {
            case None           => ZIO.fail(ServiceStreamInternalIssue(s"Media $originalId is in the timestamp index but missing from the medias collection"))
            case Some(daoMedia) =>
              daoMedia2Media(daoMedia)
                .mapError(err => ServiceStreamInternalIssue(s"Couldn't convert media: $err"))
                .map(media => MediaAccessKey(timestamp, originalId) -> media)
          }
      }
  }

  override def mediaGet(key: MediaAccessKey): IO[ServiceIssue, Option[MediaTuple]] = {
    collections.medias
      .fetch(key.toNative.originalId)
      .mapError(err => ServiceDatabaseIssue(s"Couldn't fetch media : $err"))
      .some
      .flatMap(daoMediaToMediaTuple)
      .unsome
      .logError(s"Couldn't fetch media for key ${key.asString}")
  }

  def mediaGet(id: OriginalId): IO[ServiceIssue, Option[MediaTuple]] = {
    collections.medias
      .fetch(id)
      .mapError(err => ServiceDatabaseIssue(s"Couldn't fetch media : $err"))
      .some
      .flatMap(daoMediaToMediaTuple)
      .unsome
      .logError(s"Couldn't fetch media for id $id")
  }

  // Atomically allocate the next position slot for a newly inserted media.
  // Idempotent: re-running for an originalId already indexed is a no-op.
  private def assignNextPosition(originalId: OriginalId): IO[LMDBIssues, Unit] = {
    collections.originalIdByPosition.readWrite { ops =>
      ops.last().flatMap {
        case Some((maxPos, existingId)) if existingId == originalId => ZIO.unit
        case Some((maxPos, _))                                      => ops.index(maxPos + 1L, originalId)
        case None                                                   => ops.index(0L, originalId)
      }
    }
  }

  override def mediaMaxPosition(): IO[ServiceIssue, Option[Long]] = {
    collections.originalIdByPosition
      .last()
      .map(_.map(_._1))
      .mapError(err => ServiceDatabaseIssue(s"Couldn't get media max position : $err"))
  }

  override def mediaGetAt(position: Long): IO[ServiceIssue, Option[MediaTuple]] = {
    val lookup =
      collections.originalIdByPosition
        .fetch(position)
        .flatMap {
          case Some(originalId) => ZIO.some(originalId)
          case None             => collections.originalIdByPosition.next(position).map(_.map(_._2)) // gap from deletion: nearest higher slot
        }
    lookup
      .mapError(err => ServiceDatabaseIssue(s"Couldn't fetch at $position media : $err"))
      .some
      .flatMap { originalId =>
        collections.medias
          .fetch(originalId)
          .mapError(err => Option(ServiceDatabaseIssue(s"Couldn't get media: $err")))
          .someOrFail(None)
          .flatMap(daoMediaToMediaTuple)
      }
      .unsome
      .logError(s"Couldn't fetch at $position media")
  }

  override def mediaUpdate(
    key: MediaAccessKey,
    updatedMedia: Media
  ): IO[ServiceIssue, Option[MediaTuple]] = {
    val originalId = key.toNative.originalId
    collections.medias
      .update(originalId, _ => updatedMedia.transformInto[DaoMedia](using DaoMedia.transformer)) // to solve ambiguity with auto-derived transformer
      .mapError(err => ServiceDatabaseIssue(s"Couldn't update media : $err"))
      .flatMap(mayBeDaoMedia => ZIO.foreach(mayBeDaoMedia)(daoMedia => daoMedia2Media(daoMedia).map(key -> _)))
  }

  def mediaUpdate(
    id: OriginalId,
    updatedMedia: Media
  ): IO[ServiceIssue, Option[MediaTuple]] = {
    collections.medias
      .update(id, _ => updatedMedia.transformInto[DaoMedia](using DaoMedia.transformer)) // to solve ambiguity with auto-derived transformer
      .mapError(err => ServiceDatabaseIssue(s"Couldn't update media : $err"))
      .flatMap(mayBeDaoMedia => ZIO.foreach(mayBeDaoMedia)(daoMedia => daoMedia2Media(daoMedia).map(media => buildMediaAccessKey(media) -> media)))
  }

  // -------------------------------------------------------------------------------------------------------------------
  override def mediaNormalizedRead(key: MediaAccessKey): Stream[ServiceStreamIssue, Byte] = {
    val pathEffect: IO[ServiceStreamIssue, java.nio.file.Path] = for {
      mediaTuple <- mediaGet(key)
                      .mapError(err => ServiceStreamInternalIssue(s"Couldn't fetch media for key ${key.asString} : $err"))
                      .someOrFail(ServiceStreamInternalIssue(s"Couldn't find media for key : ${key.asString}"))
      onorm      <- originalNormalized(mediaTuple.media.original.id)
                      .mapError(err => ServiceStreamInternalIssue(s"Couldn't retrieve normalized info for original ${mediaTuple.media.original.id.asString} : $err"))
                      .someOrFail(ServiceStreamInternalIssue(s"Couldn't get normalized information for original : ${mediaTuple.media.original.id.asString}"))
      norm       <- ZIO
                      .fromOption(onorm.normalized)
                      .mapError(_ => ServiceStreamInternalIssue(s"Normalized image not available for original : ${mediaTuple.media.original.id.asString}"))
      path        = norm.path.path
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
      mediaTuple <- mediaGet(key)
                      .mapError(err => ServiceStreamInternalIssue(s"Couldn't fetch media for key ${key.asString} : $err"))
                      .someOrFail(ServiceStreamInternalIssue(s"Couldn't find media for key : ${key.asString}"))
    } yield mediaTuple.media.original.absoluteMediaPath

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
        mediaTuple <- mediaGet(key)
                        .mapError(err => ServiceStreamInternalIssue(s"Couldn't fetch media for key ${key.asString} : $err"))
                        .someOrFail(ServiceStreamInternalIssue(s"Couldn't find media for key : ${key.asString}"))
        // ensure miniatures info is computed/stored (best effort)
        _          <- originalMiniatures(mediaTuple.media.original.id).either
        sizes      <- MiniaturizerConfig.config.map(_.referenceSizes).mapError(err => ServiceStreamInternalIssue(err.toString))
        size        = sizes.maxOption.getOrElse(256)
        path       <- MiniaturizeProcessor
                        .getOriginalMiniatureFilePath(mediaTuple.media.original, size)
                        .mapError(err => ServiceStreamInternalIssue(s"Couldn't compute miniature path: $err"))
        exists     <- ZIO.attempt(path.toFile.exists()).mapError(th => ServiceStreamInternalIssue(s"Couldn't check file existence $path : $th"))
        stream     <- if (exists) {
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
    collections.states
      .stream()
      .map(daoState => daoState.transformInto[State])
      .mapError(err => ServiceStreamInternalIssue(s"Couldn't collect states : $err"))
  }
  def stateGet(originalId: OriginalId): IO[ServiceIssue, Option[State]]          = {
    collections.states
      .fetch(originalId)
      .map(maybeDaoState => maybeDaoState.map(_.transformInto[State]))
      .mapError(err => ServiceDatabaseIssue(s"Couldn't fetch state : $err"))
  }
  def stateDelete(originalId: OriginalId): IO[ServiceIssue, Unit]                = {
    collections.states
      .delete(originalId)
      .mapError(err => ServiceDatabaseIssue(s"Couldn't delete state : $err"))
      .unit
  }
  def stateUpsert(originalId: OriginalId, state: State): IO[ServiceIssue, State] = {
    collections.states
      .upsert(originalId, _ => state.transformInto[DaoState])
      .mapError(err => ServiceDatabaseIssue(s"Couldn't create or update state : $err"))
      .as(state)
  }

  // -------------------------------------------------------------------------------------------------------------------

  def faceList(): Stream[ServiceStreamIssue, Face] = {
    collections.detectedFaces
      .stream()
      .map(daoFace => daoFace.transformInto[Face])
      .mapError(err => ServiceStreamInternalIssue(s"Couldn't collect medias : $err"))
  }

  def faceCount(): IO[ServiceIssue, Long] = {
    collections.detectedFaces
      .size()
      .mapError(err => ServiceDatabaseIssue(s"Couldn't count faces : $err"))
  }

  def faceGet(faceId: FaceId): IO[ServiceIssue, Option[Face]] = {
    collections.detectedFaces
      .fetch(faceId)
      .map(maybeDaoFace => maybeDaoFace.map(_.transformInto[Face]))
      .mapError(err => ServiceDatabaseIssue(s"Couldn't fetch face : $err"))
  }

  def faceExists(faceId: FaceId): IO[ServiceIssue, Boolean] = {
    collections.detectedFaces
      .contains(faceId)
      .mapError(err => ServiceDatabaseIssue(s"Couldn't check if face exists : $err"))
  }

  def faceDelete(faceId: FaceId): IO[ServiceIssue, Unit] = {
    for {
      face <- faceGet(faceId).some.orElseFail(ServiceUserIssue(s"Couldn't find face to delete : $faceId"))
      _    <- lmdb
                .readWrite { ops =>
                  val detectedFacesTX = collections.detectedFaces.lift(ops)
                  val faceFeaturesTX  = collections.faceFeatures.lift(ops)
                  val originalFacesTX = collections.originalFaces.lift(ops)
                  detectedFacesTX.delete(faceId) *>
                    faceFeaturesTX.delete(faceId) *>
                    originalFacesTX.update(
                      face.originalId,
                      previous => previous.copy(facesIds = previous.facesIds.filterNot(_ == faceId))
                    )
                }
                .mapError(err => ServiceInternalIssue(s"Couldn't delete face : $err"))
      // TODO the only risk is to get orphan face files if delete fails
      _    <- ZIO
                .attempt(face.path.path.toFile.delete())
                .mapError(th => ServiceInternalIssue(s"Couldn't delete face file : $th"))
    } yield ()
  }

  def faceCreate(faceId: Option[FaceId], originalId: OriginalId, box: BoundingBox): IO[ServiceIssue, Face] = {
    // TODO require transactions
    for {
      original            <- originalGet(originalId)
                               .someOrFail(ServiceDatabaseIssue(s"Couldn't find original : $originalId"))
                               .logError(s"Couldn't find original : $originalId")
      facesProcessor      <- processors.faces
                               .mapError(err => ServiceInternalIssue(s"Unable to get original detected faces processor : $err"))
      builtFace           <- facesProcessor
                               .buildDetectedFace(original, box)
                               .mapError(err => ServiceInternalIssue(s"Couldn't build face : $err"))
      bufferedImage       <- facesProcessor
                               .getOriginalBufferedImage(original)
                               .mapError(err => ServiceInternalIssue(s"Couldn't get original image : $err"))
      _                   <- facesProcessor
                               .extractThenCacheFaceImageFromOriginal(builtFace, bufferedImage)
                               .mapError(err => ServiceInternalIssue(s"Couldn't extract face image : $err"))
      _                   <- collections.detectedFaces
                               .upsertOverwrite(builtFace.faceId, builtFace.into[DaoDetectedFace].transform)
                               .mapError(err => ServiceDatabaseIssue(s"Couldn't create face : $err"))
      originalFaces       <- originalFaces(original.id)
      updatedOriginalFaces = builtFace.faceId :: originalFaces.map(_.faces.map(_.faceId)).getOrElse(Nil)
      _                   <- originalFacesUpdate(originalId, updatedOriginalFaces)
      _                   <- originalFacesFeatures(originalId)
    } yield builtFace
  }

  def faceUpdate(
    faceId: FaceId, // current face id
    face: Face      // may contain and updated id
  ): IO[ServiceIssue, Face] = {
    if (face.faceId == faceId) {
      collections.detectedFaces
        .upsert(faceId, _ => face.transformInto[DaoDetectedFace])
        .mapError(err => ServiceDatabaseIssue(s"Couldn't update face : $err"))
        .as(face)
    } else {
      // TODO require transactions
      // id has been modified require delete record & then insert with the new access key
      // TODO dangerous operation in particular because no transaction to ensure coherency, making it uninterrruptible is not enough
      (collections.detectedFaces.delete(faceId).unit *> collections.detectedFaces.upsert(face.faceId, _ => face.transformInto[DaoDetectedFace])).uninterruptible
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

  def faceFeaturesList(): Stream[ServiceStreamIssue, FaceFeatures] = {
    collections.faceFeatures
      .stream()
      .map(_.transformInto[FaceFeatures])
      .mapError(err => ServiceStreamInternalIssue(s"Couldn't collect face features : $err"))
  }

  def faceFeaturesGet(faceId: FaceId): IO[ServiceIssue, Option[FaceFeatures]] = {
    collections.faceFeatures
      .fetch(faceId)
      .map(_.map(_.transformInto[FaceFeatures]))
      .mapError(err => ServiceDatabaseIssue(s"Couldn't fetch face features : $err"))
  }

  // -------------------------------------------------------------------------------------------------------------------

  def personList(): Stream[ServiceStreamIssue, Person] = {
    collections.persons
      .stream()
      .map(daoPerson => daoPerson.transformInto[Person])
      .mapError(err => ServiceStreamInternalIssue(s"Couldn't collect persons : $err"))
  }

  def personCount(): IO[ServiceIssue, Long] = {
    collections.persons
      .size()
      .mapError(err => ServiceDatabaseIssue(s"Couldn't count persons : $err"))
  }

  def personGet(personId: PersonId): IO[ServiceIssue, Option[Person]] = {
    collections.persons
      .fetch(personId)
      .map(_.map(_.transformInto[Person]))
      .mapError(err => ServiceDatabaseIssue(s"Couldn't fetch person : $err"))
  }

  def personExists(personId: PersonId): IO[ServiceIssue, Boolean] = {
    collections.persons
      .contains(personId)
      .mapError(err => ServiceDatabaseIssue(s"Couldn't check if person exists : $err"))
  }

  def personDelete(personId: PersonId): IO[ServiceIssue, Unit] = {
    for {
      facesToUpdate <- collections.detectedFaces
                         .stream()
                         .filter(df => df.identifiedPersonId.contains(personId) || df.inferredIdentifiedPersonId.contains(personId))
                         .map(df =>
                           df.copy(
                             identifiedPersonId = df.identifiedPersonId.filterNot(_ == personId),
                             inferredIdentifiedPersonId = df.inferredIdentifiedPersonId.filterNot(_ == personId)
                           )
                         )
                         .runCollect
                         .mapError(err => ServiceDatabaseIssue(s"Couldn't collect faces for person $personId : $err"))
      _             <- ZIO.foreachDiscard(facesToUpdate)(df =>
                         collections.detectedFaces
                           .upsertOverwrite(df.faceId, df)
                           .mapError(err => ServiceDatabaseIssue(s"Couldn't update face ${df.faceId} : $err"))
                       )
      _             <- collections.persons
                         .delete(personId)
                         .mapError(err => ServiceDatabaseIssue(s"Couldn't delete person : $err"))
    } yield ()
  }

  def personCreate(
    id: Option[PersonId],
    firstName: FirstName,
    lastName: LastName,
    birthName: Option[BirthName],
    birthDate: Option[BirthDate],
    email: Option[PersonEmail],
    description: Option[PersonDescription]
  ): IO[ServiceIssue, Person] = {
    for {
      personId <- id.map(ZIO.succeed).getOrElse(ZIO.succeed(PersonId(ULID.newULID)))
      person    = Person(personId, firstName = firstName, lastName = lastName, birthName = birthName, birthDate = birthDate, email = email, description = description, chosenFaceId = None)
      _        <- collections.persons
                    .upsertOverwrite(personId, person.into[DaoPerson].transform)
                    .mapError(err => ServiceDatabaseIssue(s"Couldn't create person : $err"))
    } yield person
  }

  def personUpdate(
    personId: PersonId,
    firstName: FirstName,
    lastName: LastName,
    birthName: Option[BirthName],
    birthDate: Option[BirthDate],
    email: Option[PersonEmail],
    description: Option[PersonDescription],
    chosenFaceId: Option[FaceId]
  ): IO[ServiceIssue, Option[Person]] = {
    for {
      maybeDaoPerson <- collections.persons
                          .update(
                            personId,
                            _.copy(
                              firstName = firstName,
                              lastName = lastName,
                              birthName = birthName,
                              birthDate = birthDate,
                              email = email,
                              description = description,
                              chosenFaceId = chosenFaceId
                            )
                          )
                          .mapError(err => ServiceDatabaseIssue(s"Couldn't update owner : $err"))
    } yield maybeDaoPerson.map(_.transformInto[Person])
  }

  def personFaceList(personId: PersonId): Stream[ServiceStreamIssue, Face] = {
    collections.faceIdByPersonId
      .indexed(personId)
      .mapZIO { case (personId, (timestamp, faceId)) => collections.detectedFaces.fetch(faceId) }
      .filter(_.isDefined)
      .map(_.get.transformInto[Face])
      .mapError(err => ServiceStreamInternalIssue(s"Couldn't collect faces for person $personId : $err"))
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
      processor <- processors.classifications
                     .mapError(err => ServiceInternalIssue(s"Unable to get original classifications processor: $err"))
      computed  <- processor
                     .classify(original)
                     .mapError(err => ServiceInternalIssue(s"Unable to extract original classifications : $err"))
      _         <- collections.classifications
                     .upsertOverwrite(originalId, computed.into[DaoOriginalClassifications].transform)
                     .mapError(err => ServiceDatabaseIssue(s"Unable to store computed classifications : $err"))
    } yield computed
    logic.uninterruptible
  }

  override def originalClassifications(originalId: OriginalId): IO[ServiceIssue, Option[OriginalClassifications]] = {
    for {
      stored <- collections.classifications
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
      processor <- processors.faces
                     .mapError(err => ServiceInternalIssue(s"Unable to get original detected faces processor : $err"))
      computed  <- processor
                     .extractFaces(original)
                     .mapError(err => ServiceInternalIssue(s"Unable to extract original detected faces : $err"))
      _         <- collections.originalFaces
                     .upsertOverwrite(originalId, computed.into[DaoOriginalFaces].transform)
                     .mapError(err => ServiceDatabaseIssue(s"Unable to store computed faces : $err"))
      _         <- ZIO.foreachDiscard(computed.faces)(face =>
                     collections.detectedFaces
                       .upsertOverwrite(face.faceId, face.into[DaoDetectedFace].transform)
                       .mapError(err => ServiceDatabaseIssue(s"Unable to store computed detected face : $err"))
                   )
    } yield computed
    logic.uninterruptible
  }

  override def originalFaces(originalId: OriginalId): IO[ServiceIssue, Option[OriginalFaces]] = {
    for {
      stored <- collections.originalFaces
                  .fetch(originalId)
                  .flatMap(mayBeFound => ZIO.foreach(mayBeFound)(daoFacesToFaces))
                  .mapError(err => ServiceDatabaseIssue(s"Unable to fetch faces from database: $err"))
      // .tap(stored => Console.printLine(s"Stored : $stored").orDie)
      result <- computeFaces(originalId).when(stored.isEmpty)
    } yield stored.orElse(result)
  }

  def computeFaceFeatures(originalId: OriginalId): IO[ServiceIssue, OriginalFaceFeatures] = {
    // TODO transaction required
    val logic = for {
      originalFaces <- originalFaces(originalId)
                         .someOrFail(ServiceDatabaseIssue(s"Couldn't find original : $originalId"))
      processor     <- processors.faceFeatures
                         .mapError(err => ServiceInternalIssue(s"Unable to get original detected faces features processor : $err"))
      computed      <- processor
                         .extractFaceFeatures(originalFaces)
                         .mapError(err => ServiceInternalIssue(s"Unable to extract original detected faces features : $err"))
      _             <- ZIO.foreachDiscard(computed.features)(face =>
                         collections.faceFeatures
                           .upsertOverwrite(face.faceId, face.into[DaoFaceFeatures].transform)
                           .mapError(err => ServiceDatabaseIssue(s"Unable to store computed detected face : $err"))
                       )
      _             <- collections.originalFaceFeatures
                         .upsertOverwrite(
                           originalId,
                           computed
                             .into[DaoOriginalFaceFeatures]
                             .withFieldComputed(_.originalId, _.original.id)
                             .transform
                         )
                         .mapError(err => ServiceDatabaseIssue(s"Unable to store computed faces : $err"))
    } yield computed
    logic.uninterruptible
  }

  def daoFacesFeaturesToFacesFeatures(input: DaoOriginalFaceFeatures): IO[ServiceIssue, OriginalFaceFeatures] = {
    for {
      original      <- originalGet(input.originalId).someOrFail(ServiceDatabaseIssue(s"Couldn't find original : ${input.originalId}"))
      originalFaces <- originalFaces(original.id).map(_.map(_.faces).getOrElse(Nil))
      facesFeatures <- ZIO.foreach(originalFaces)(face => faceFeaturesGet(face.faceId))
      result         = input
                         .into[OriginalFaceFeatures]
                         .withFieldConst(_.original, original)
                         .withFieldConst(_.features, facesFeatures.flatten)
                         .transform
    } yield result
  }

  override def originalFacesFeatures(originalId: OriginalId): IO[ServiceIssue, Option[OriginalFaceFeatures]] = {
    for {
      faceIds            <- originalFaces(originalId).map(_.map(_.faces.map(_.faceId).toSet).getOrElse(Set.empty))
      faceFeatures       <- collections.originalFaceFeatures
                              .fetch(originalId)
                              .flatMap(gotten => ZIO.foreach(gotten)(daoFacesFeaturesToFacesFeatures))
                              .mapError(err => ServiceDatabaseIssue(s"Unable to fetch faces from database: $err"))
      faceFeaturesFaceIds = faceFeatures.map(_.features.map(_.faceId).toSet).getOrElse(Set.empty)
      result             <- computeFaceFeatures(originalId)
                              .tap(feats => ZIO.logInfo(s"Computed face features for original $originalId : ${feats.features.size}"))
                              .when(faceFeatures.isEmpty || faceFeaturesFaceIds != faceIds)
    } yield faceFeatures.orElse(result)
  }

  override def originalFacesFeaturesRecompute(media: Media): IO[ServiceIssue, Option[OriginalFaceFeatures]] = {
    val original = media.original
    val rotation = media.orientation.orElse(original.orientation).map(_.rotationDegrees).getOrElse(0)
    val logic    = for {
      facesProcessor    <- processors.faces
                             .mapError(err => ServiceInternalIssue(s"Unable to get faces processor : $err"))
      featuresProcessor <- processors.faceFeatures
                             .mapError(err => ServiceInternalIssue(s"Unable to get face features processor : $err"))
      rawImage          <- ZIO
                             .attemptBlocking(fr.janalyse.sotohp.media.imaging.BasicImaging.load(original.absoluteMediaPath))
                             .mapError(th => ServiceInternalIssue(s"Couldn't load original image : $th"))
      rotatedImage      <- ZIO
                             .attemptBlocking(fr.janalyse.sotohp.media.imaging.BasicImaging.rotate(rawImage, rotation))
                             .mapError(th => ServiceInternalIssue(s"Couldn't rotate original image : $th"))
      // Drop all existing faces (and their dependent data) before re-detecting on the rotated image.
      existingFaceIds   <- originalFaces(original.id).map(_.map(_.faces.map(_.faceId)).getOrElse(Nil))
      _                 <- ZIO.foreachDiscard(existingFaceIds)(faceDelete)
      // Re-detect faces with new bounding boxes from the rotated image.
      detected          <- facesProcessor
                             .extractFaces(original, rotatedImage)
                             .mapError(err => ServiceInternalIssue(s"Unable to re-detect faces : $err"))
      _                 <- collections.originalFaces
                             .upsertOverwrite(original.id, detected.into[DaoOriginalFaces].transform)
                             .mapError(err => ServiceDatabaseIssue(s"Unable to store computed faces : $err"))
      _                 <- ZIO.foreachDiscard(detected.faces) { face =>
                             collections.detectedFaces
                               .upsertOverwrite(face.faceId, face.into[DaoDetectedFace].transform)
                               .mapError(err => ServiceDatabaseIssue(s"Unable to store computed detected face : $err"))
                           }
      // Compute features on the freshly-detected faces using the same rotated image.
      computed          <- featuresProcessor
                             .extractFaceFeatures(detected, rotatedImage)
                             .mapError(err => ServiceInternalIssue(s"Unable to extract original detected faces features : $err"))
      _                 <- ZIO.foreachDiscard(computed.features) { face =>
                             collections.faceFeatures
                               .upsertOverwrite(face.faceId, face.into[DaoFaceFeatures].transform)
                               .mapError(err => ServiceDatabaseIssue(s"Unable to store computed detected face : $err"))
                           }
      _                 <- collections.originalFaceFeatures
                             .upsertOverwrite(
                               original.id,
                               computed
                                 .into[DaoOriginalFaceFeatures]
                                 .withFieldComputed(_.originalId, _.original.id)
                                 .transform
                             )
                             .mapError(err => ServiceDatabaseIssue(s"Unable to store computed faces : $err"))
    } yield Some(computed)
    logic.uninterruptible
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
      processor <- processors.objects
                     .mapError(err => ServiceInternalIssue(s"Unable to get original detected objects processor : $err"))
      computed  <- processor
                     .extractObjects(original)
                     .mapError(err => ServiceInternalIssue(s"Unable to extract original detected objects : $err"))
      _         <- collections.objects
                     .upsertOverwrite(originalId, computed.into[DaoOriginalDetectedObjects].transform)
                     .mapError(err => ServiceDatabaseIssue(s"Unable to store computed detected objects : $err"))
    } yield computed
    logic.uninterruptible
  }

  override def originalObjects(originalId: OriginalId): IO[ServiceIssue, Option[OriginalDetectedObjects]] = {
    for {
      stored <- collections.objects
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
      _        <- collections.normalized
                    .upsertOverwrite(originalId, computed.into[DaoOriginalNormalized].transform)
                    .mapError(err => ServiceDatabaseIssue(s"Unable to store computed normalized original : $err"))
    } yield computed
  }

  override def originalNormalized(originalId: OriginalId): IO[ServiceIssue, Option[OriginalNormalized]] = {
    for {
      stored <- collections.normalized
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
      _        <- collections.miniatures
                    .upsertOverwrite(originalId, computed.into[DaoOriginalMiniatures].transform)
                    .mapError(err => ServiceDatabaseIssue(s"Unable to store computed miniatures : $err"))
    } yield computed
  }

  override def originalMiniatures(originalId: OriginalId): IO[ServiceIssue, Option[OriginalMiniatures]] = {
    for {
      stored <- collections.miniatures
                  .fetch(originalId)
                  .flatMap(mayBeFound => ZIO.foreach(mayBeFound)(daoMiniaturesToMiniatures))
                  .mapError(err => ServiceDatabaseIssue(s"Unable to fetch normalized original from database: $err"))
      result <- computeMiniatures(originalId).when(stored.isEmpty)
    } yield stored.orElse(result)
  }

  override def originalFacesUpdate(originalId: OriginalId, facesIds: List[FaceId]): IO[ServiceIssue, Unit] = {
    for {
      originalFacesDao <- collections.originalFaces
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
    collections.originals
      .stream()
      .mapZIO(daoOriginal2Original)
      .mapError(err => ServiceStreamInternalIssue(s"Couldn't collect originals : $err"))
  }

  override def originalCount(): IO[ServiceIssue, Long] = for {
    count <- collections.originals
               .size()
               .mapError(err => ServiceDatabaseIssue(s"Couldn't count originals : $err"))
  } yield count

  override def originalGet(originalId: OriginalId): IO[ServiceIssue, Option[Original]] = for {
    maybeDaoOriginal <- collections.originals.fetch(originalId).mapError(err => ServiceDatabaseIssue(s"Couldn't fetch original : $err"))
    maybeOriginal    <- ZIO.foreach(maybeDaoOriginal)(daoOriginal2Original)
  } yield maybeOriginal

  override def originalExists(originalId: OriginalId): IO[ServiceIssue, Boolean] =
    collections.originals.contains(originalId).mapError(err => ServiceDatabaseIssue(s"Couldn't lookup original : $err"))

  override def originalDelete(originalId: OriginalId): IO[ServiceIssue, Unit] = {
    collections.originals
      .delete(originalId)
      .mapError(err => ServiceDatabaseIssue(s"Couldn't delete original : $err"))
      .unit
  }

  override def originalUpsert(providedOriginal: Original): IO[ServiceIssue, Original] = {
    collections.originals
      .upsert(providedOriginal.id, previous => providedOriginal.into[DaoOriginal].transform)
      .mapError(err => ServiceDatabaseIssue(s"Couldn't create or update original : $err"))
      .as(providedOriginal)
  }

  // -------------------------------------------------------------------------------------------------------------------

  def daoBag2Bag(daoBag: DaoBag): IO[ServiceIssue, Bag] = {
    for {
      store <- storeGet(daoBag.attachment.storeId)
                 .someOrFail(ServiceDatabaseIssue(s"Couldn't find store for bag attachment : ${daoBag.attachment.storeId}"))
      attachment = BagAttachment(store, daoBag.attachment.bagMediaDirectory)
      bag        = daoBag
                     .into[Bag]
                     .withFieldConst(_.attachment, attachment)
                     .withFieldComputed(_.publishedOn, in => in.publishedOn.flatMap(uri => Try(java.net.URI(uri).toURL).toOption))
                     .transform
    } yield bag
  }

  override def bagList(): Stream[ServiceStreamIssue, Bag] = {
    collections.bags
      .stream()
      .mapZIO(daoBag2Bag)
      .mapError(err => ServiceStreamInternalIssue(s"Couldn't collect bags : $err"))
  }

  override def bagGet(bagId: BagId): IO[ServiceIssue, Option[Bag]] = for {
    maybeDaoBag <- collections.bags.fetch(bagId).mapError(err => ServiceDatabaseIssue(s"Couldn't fetch bag : $err"))
    maybeBag    <- ZIO.foreach(maybeDaoBag)(daoBag2Bag)
  } yield maybeBag

  override def bagDelete(bagId: BagId): IO[ServiceIssue, Unit] = {
    for {
      maybeDaoBag <- collections.bags
                       .fetch(bagId)
                       .mapError(err => ServiceDatabaseIssue(s"Couldn't fetch bag : $err"))
      _           <- ZIO.foreachDiscard(maybeDaoBag) { _ =>
                       for {
                         // Refuse delete if any media still references this bag
                         firstLinkedMedia <- collections.originalIdByBagId
                                               .indexed(bagId)
                                               .runHead
                                               .mapError(err => ServiceDatabaseIssue(s"Couldn't check bag links : $err"))
                         _                <- ZIO
                                               .fail(ServiceUserIssue(s"Bag ${bagId.asString} is still linked to one or more medias - unlink them first"))
                                               .when(firstLinkedMedia.isDefined)
                         _                <- collections.bags
                                               .delete(bagId)
                                               .mapError(err => ServiceDatabaseIssue(s"Couldn't delete bag : $err"))
                       } yield ()
                     }
    } yield ()
  }

  override def bagUpdate(
    bagId: BagId,
    name: BagName,
    description: Option[BagDescription],
    location: Option[Location],
    timestamp: Option[ShootDateTime],
    coverOriginalId: Option[OriginalId],
    publishedOn: Option[URL],
    keywords: Set[Keyword]
  ): IO[ServiceIssue, Option[Bag]] = {
    for {
      maybeDaoBag <- collections.bags
                       .update(
                         bagId,
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
      bag         <- ZIO.foreach(maybeDaoBag)(daoBag2Bag)
    } yield bag

  }

  // -------------------------------------------------------------------------------------------------------------------

  private def daoPortfolio2Portfolio(daoPortfolio: DaoPortfolio, assets: List[Asset]): Portfolio =
    daoPortfolio
      .into[Portfolio]
      .withFieldConst(_.assets, assets)
      .transform

  // Assets ordered by the shoot date of their media, oldest first;
  // assets whose media is unknown are pushed to the end.
  private def sortAssetsByShootDate(daoAssets: List[DaoAsset]) =
    ZIO
      .foreach(daoAssets) { daoAsset =>
        collections.medias
          .fetch(daoAsset.originalId)
          .map(maybeDaoMedia => maybeDaoMedia.map(_.timestamp) -> daoAsset)
      }
      .map(
        _.sortBy((timestamp, _) => timestamp.map(_.toInstant.toEpochMilli).getOrElse(Long.MaxValue))
          .map((_, daoAsset) => daoAsset.transformInto[Asset])
      )

  override def portfolioList(): Stream[ServiceStreamIssue, Portfolio] = {
    collections.portfolios
      .stream()
      .mapError(err => ServiceStreamInternalIssue(s"Couldn't collect portfolios : $err"))
      .mapZIO { daoPortfolio =>
        collections.portfolioAssets
          .fetch(daoPortfolio.id)
          .flatMap(sortAssetsByShootDate)
          .mapBoth(
            err => ServiceStreamInternalIssue(s"Couldn't fetch portfolio assets : $err"),
            assets => daoPortfolio2Portfolio(daoPortfolio, assets)
          )
      }
  }

  override def portfolioGet(portfolioId: PortfolioId): IO[ServiceIssue, Option[Portfolio]] = {
    for {
      maybeDao <- collections.portfolios
                    .fetch(portfolioId)
                    .mapError(err => ServiceDatabaseIssue(s"Couldn't fetch portfolio : $err"))
      result   <- ZIO.foreach(maybeDao) { dao =>
                    collections.portfolioAssets
                      .fetch(portfolioId)
                      .flatMap(sortAssetsByShootDate)
                      .mapBoth(
                        err => ServiceDatabaseIssue(s"Couldn't fetch portfolio assets : $err"),
                        assets => daoPortfolio2Portfolio(dao, assets)
                      )
                  }
    } yield result
  }

  override def portfolioCreate(
    name: PortfolioName,
    description: Option[PortfolioDescription]
  ): IO[ServiceIssue, Portfolio] = {
    for {
      portfolioId <- Random.nextUUID.map(PortfolioId.apply)
      portfolio    = Portfolio(portfolioId, name, description, Nil)
      _           <- collections.portfolios
                       .upsert(portfolioId, _ => portfolio.transformInto[DaoPortfolio])
                       .mapError(err => ServiceDatabaseIssue(s"Couldn't create portfolio : $err"))
    } yield portfolio
  }

  override def portfolioUpdate(
    portfolioId: PortfolioId,
    name: PortfolioName,
    description: Option[PortfolioDescription]
  ): IO[ServiceIssue, Option[Portfolio]] = {
    for {
      maybeDao <- collections.portfolios
                    .update(
                      portfolioId,
                      _.copy(name = name, description = description)
                    )
                    .mapError(err => ServiceDatabaseIssue(s"Couldn't update portfolio : $err"))
      result   <- ZIO.foreach(maybeDao) { dao =>
                    collections.portfolioAssets
                      .fetch(portfolioId)
                      .mapBoth(
                        err => ServiceDatabaseIssue(s"Couldn't fetch portfolio assets : $err"),
                        daoAssets => daoPortfolio2Portfolio(dao, daoAssets.map(_.transformInto[Asset]))
                      )
                  }
    } yield result
  }

  override def portfolioDelete(portfolioId: PortfolioId): IO[ServiceIssue, Unit] = {
    for {
      _ <- collections.portfolioAssets
             .deleteAll(portfolioId)
             .mapError(err => ServiceDatabaseIssue(s"Couldn't delete portfolio assets : $err"))
      _ <- collections.portfolios
             .delete(portfolioId)
             .mapError(err => ServiceDatabaseIssue(s"Couldn't delete portfolio : $err"))
    } yield ()
  }

  override def portfolioAssetAdd(
    portfolioId: PortfolioId,
    asset: Asset
  ): IO[ServiceIssue, Asset] = {
    for {
      _ <- collections.portfolios
             .fetch(portfolioId)
             .mapError(err => ServiceDatabaseIssue(s"Couldn't fetch portfolio : $err"))
             .someOrFail(ServiceUserIssue(s"Portfolio not found : ${portfolioId.asString}"))
      _ <- collections.portfolioAssets
             .put(portfolioId, asset.transformInto[DaoAsset])
             .mapError(err => ServiceDatabaseIssue(s"Couldn't add portfolio asset : $err"))
    } yield asset
  }

  override def portfolioAssetUpdate(
    portfolioId: PortfolioId,
    oldAsset: Asset,
    newAsset: Asset
  ): IO[ServiceIssue, Option[Asset]] = {
    val oldDao = oldAsset.transformInto[DaoAsset]
    val newDao = newAsset.transformInto[DaoAsset]
    for {
      removed <- collections.portfolioAssets
                   .delete(portfolioId, oldDao)
                   .mapError(err => ServiceDatabaseIssue(s"Couldn't remove old portfolio asset : $err"))
      result  <- if (!removed) ZIO.succeed(Option.empty[Asset])
                 else collections.portfolioAssets
                        .put(portfolioId, newDao)
                        .mapBoth(
                          err => ServiceDatabaseIssue(s"Couldn't put updated portfolio asset : $err"),
                          _ => Some(newAsset)
                        )
    } yield result
  }

  override def portfolioAssetRemove(
    portfolioId: PortfolioId,
    asset: Asset
  ): IO[ServiceIssue, Boolean] = {
    collections.portfolioAssets
      .delete(portfolioId, asset.transformInto[DaoAsset])
      .mapError(err => ServiceDatabaseIssue(s"Couldn't remove portfolio asset : $err"))
  }

  // -------------------------------------------------------------------------------------------------------------------

  override def ownerList(): Stream[ServiceIssue, Owner] = {
    collections.owners
      .stream()
      .mapBoth(err => ServiceDatabaseIssue(s"Couldn't collect owners : $err"), daoOwner => daoOwner.transformInto[Owner])
  }

  override def ownerGet(ownerId: OwnerId): IO[ServiceIssue, Option[Owner]] = {
    collections.owners
      .fetch(ownerId)
      .mapBoth(err => ServiceDatabaseIssue(s"Couldn't fetch owner : $err"), maybeDaoOwner => maybeDaoOwner.map(_.transformInto[Owner]))
  }

  override def ownerDelete(ownerId: OwnerId): IO[ServiceIssue, Unit] = {
    collections.owners
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
      _       <- collections.owners
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
      maybeDaoOwner <- collections.owners
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
    collections.stores
      .stream()
      .map(daoStore => daoStore.transformInto[Store])
      .mapError(err => ServiceDatabaseIssue(s"Couldn't collect stores : $err"))
  }

  override def storeGet(storeId: StoreId): IO[ServiceIssue, Option[Store]] = {
    collections.stores
      .fetch(storeId)
      .mapBoth(err => ServiceDatabaseIssue(s"Couldn't fetch store : $err"), maybeDaoStore => maybeDaoStore.map(_.transformInto[Store]))
  }

  override def storeDelete(storeId: StoreId): IO[ServiceIssue, Unit] = {
    collections.stores
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
      _       <- collections.stores
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
      maybeDaoStore <- collections.stores
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

  private def getBagForAttachment(attachment: BagAttachment): IO[ServiceIssue, Option[Bag]] = {
    // TODO first basic and naive implementation - not good for complexity
    collections.bags
      .collect(valueFilter = daoFilter => daoFilter.attachment.storeId == attachment.store.id && daoFilter.attachment.bagMediaDirectory == attachment.bagMediaDirectory)
      .mapBoth(err => ServiceDatabaseIssue(s"Couldn't collect bags : $err"), _.headOption)
      .flatMap(mayBeDaoBag => ZIO.foreach(mayBeDaoBag)(daoBag2Bag))
  }

  private def createDefaultBag(original: Original, attachment: BagAttachment): IO[ServiceIssue, Bag] = {
    for {
      autoKeywords <- keywordSentenceToKeywords(attachment.store.id, attachment.bagMediaDirectory.toString)
      bagId        <- Random.nextUUID.map(BagId.apply)
      bag           = Bag(
                        id = bagId,
                        attachment = attachment,
                        name = BagName(attachment.bagMediaDirectory.toString),
                        description = None,
                        location = original.location,
                        timestamp = original.cameraShootDateTime,
                        originalId = Some(original.id),
                        publishedOn = None,
                        keywords = autoKeywords
                      )
      _            <- collections.bags
                        .upsert(bagId, _ => bag.into[DaoBag].transform)
                        .mapError(err => ServiceDatabaseIssue(s"Couldn't create bag : $err"))
    } yield bag
  }

  private def synchronizeState(original: Original): IO[ServiceIssue, (original: Original, state: State)] = {
    val relatedBagAttachment = MediaBuilder.buildBagAttachment(original)
    val logic                = for {
      mayBeBag     <- ZIO.foreach(relatedBagAttachment)(getBagForAttachment).map(_.flatten)
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
                            mediaLastSynchronized = None
                          )
                        )
      state        <- stateUpsert(original.id, updatedState)
    } yield (original, state)
    logic @@ annotated("originalId" -> original.id.toString, "originalMediaPath" -> original.absoluteMediaPath.toString)
  }

  private def synchronizeMedia(input: (original: Original, state: State)): IO[ServiceIssue, (media: Media, state: State)] = {
    val relatedBagAttachment = MediaBuilder.buildBagAttachment(input.original)
    val logic                = for {
      mayBeBag          <- ZIO
                             .foreach(relatedBagAttachment)(attachment =>
                               getBagForAttachment(attachment)
                                 .someOrElseZIO(createDefaultBag(input.original, attachment))
                             )
      currentMediaTuple <- mediaGet(input.state.originalId) // already existing media is the source of truth !
                             .someOrElseZIO {
                               val daoMedia = DaoMedia(
                                 originalId = input.original.id,
                                 bagId = mayBeBag.map(_.id),
                                 description = None,
                                 starred = Starred(false),
                                 keywords = Set.empty,
                                 orientation = None,
                                 shootDateTime = None,
                                 userDefinedLocation = None,
                                 deductedLocation = None,
                                 timestamp = Media.computeTimestamp(None, mayBeBag, input.original),
                                 location = input.original.location.transformInto[Option[DaoLocation]]
                               )
                               collections.medias
                                 .upsert(input.original.id, _ => daoMedia)
                                 .zipLeft(assignNextPosition(input.original.id))
                                 .flatMap(daoMedia => daoMedia2Media(daoMedia).map(media => (buildMediaAccessKey(media), media)))
                                 .mapError(err => ServiceDatabaseIssue(s"Couldn't create media : $err"))
                             }
    } yield (currentMediaTuple.media, input.state)
    logic @@ annotated("originalId" -> input.original.id.toString, "originalMediaPath" -> input.original.absoluteMediaPath.toString)
  }

  private def synchronizeProcessors(input: (media: Media, state: State)): IO[ServiceIssue, (media: Media, state: State)] = {
    val logic = for {
      _                         <- originalNormalized(input.media.original.id)                   // required to optimize AI work so not launched in background
      fiberMiniaturesFiber      <- originalMiniatures(input.media.original.id)                   // .fork
      fiberFacesFiber           <- originalFaces(input.media.original.id).ignoreLogged           // .fork
      fiberFeaturesFiber        <- originalFacesFeatures(input.media.original.id).ignoreLogged   // .fork
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
      def acceptable(current: MediaTuple): Boolean = {
        val sameUser       = current.media.original.store.ownerId.asULID == input.media.original.store.ownerId
        val elapsedSeconds = Math.abs(current.media.timestamp.toEpochSecond - input.media.timestamp.toEpochSecond)
        (elapsedSeconds < 3 * 3600) && sameUser
      }

      def prevCandidates =
        zstreamGenerator(mediaPrevious(buildMediaAccessKey(input.media)))(prev => mediaPrevious(buildMediaAccessKey(prev.media)))
          .takeWhile(acceptable)
          .filter(_.media.original.hasLocation)

      def nextCandidates =
        zstreamGenerator(mediaNext(buildMediaAccessKey(input.media)))(next => mediaNext(buildMediaAccessKey(next.media)))
          .takeWhile(acceptable)
          .filter(_.media.original.hasLocation)

      for {
        firstPrev    <- prevCandidates.runHead
        firstNext    <- nextCandidates.runHead
        validDistance = firstPrev
                          .flatMap(_.media.original.location)
                          .flatMap(fp => firstNext.flatMap(_.media.original.location).map(fn => fp.distanceTo(fn)))
                          .exists(_ < 750) // meters // TODO add config parameter
        inductedLocationInMiddle  = if (validDistance)
                                      firstPrev.flatMap(_.media.original.location)
                                    else None
        inductedLocationFirstShot = if (
                                      firstPrev.isEmpty
                                      && firstNext.isDefined
                                      && firstNext.exists(fn => fn.media.timestamp.toEpochSecond - input.media.timestamp.toEpochSecond < 30 * 60) // 30 minutes // TODO add config parameter
                                    )
                                      firstNext.flatMap(_.media.original.location)
                                    else None
        inductedLocation          = inductedLocationFirstShot.orElse(inductedLocationInMiddle)
        updatedMedia              = input.media.copy(deductedLocation = inductedLocation)
        _                        <- mediaUpdate(input.media.original.id, updatedMedia).when(inductedLocation.isDefined)
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

    val searchFilter = new SearchFilter {
      override def fileLastModifiedCriteria: FileLastModified => Boolean = fileLastModified => addedThoseLastDays.isEmpty || fileLastModified.offsetDateTime.isAfter(OffsetDateTime.now().minusDays(addedThoseLastDays.get))
    }
    val syncLogic    = {
      for {
        _              <- ZIO.log(s"Synchronization started addedThoseLastDays=$addedThoseLastDays")
        stores         <- storeList().runCollect
        serviceConfig  <- ServiceConfig.config
                            .mapError(err => ServiceInternalIssue(s"Unable to retrieve service configuration : $err"))
        searchConfig    = serviceConfig.fileSystemSearch.toCoreConfig
        originalsStream = ZStream
                            .from(stores)
                            .mapZIO(store => ZIO.attemptBlocking(FileSystemSearch.originalsStreamFromSearchRoot(store, searchConfig, Some(searchFilter))))
                            .absolve
                            .flatMap(javaStream => ZStream.fromJavaStream(javaStream))
                            .right
        _              <- originalsStream
                            // .tap(original => ZIO.logInfo(s"Checking ${original.mediaPath}"))
                            .tap(_ => updateSynchronizeCheckedStatus())
                            .mapZIO(original => ZIO.blocking(synchronizeOriginal(original)))
                            .mapZIO(original => ZIO.blocking(synchronizeState(original)))
                            .mapZIO(input => ZIO.blocking(synchronizeMedia(input)))
                            .filter(_.state.mediaLastSynchronized.isEmpty)
                            .tap(input => ZIO.logInfo(s"Synchronizing ${input.media.original.mediaPath}"))
                            .mapZIO(input => ZIO.blocking(synchronizeProcessors(input).uninterruptible))
                            .mapZIO(input => ZIO.blocking(locationInduction(input).uninterruptible))
                            .grouped(50)
                            .mapZIO(input => ZIO.blocking(synchronizeSearchEngine(input).uninterruptible))
                            .mapZIO(input => ZIO.blocking(updateSynchronizeProcessedStatus(input).uninterruptible))
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

  override def reindexAll(): IO[ServiceIssue, Unit] = {
    // The positional index is not attached via withIndexFull because assigning a
    // position requires reading the current max — withIndexFull's extractor is pure.
    // Rebuild it by walking the medias collection and writing sequential positions.
    val rebuildPositional =
      collections.originalIdByPosition.readWrite { ops =>
        for {
          _ <- ops.ops.indexClear(collections.originalIdByPosition.name)
          _ <- collections.medias
                 .streamWithKeys()
                 .zipWithIndex
                 .runForeach { case ((originalId, _), idx) => ops.index(idx, originalId) }
        } yield ()
      }

    // daoMedia.location is a denormalized field used by the GEO index. It was
    // added after the initial schema and is only populated when a media is
    // written via the Media→DaoMedia transformer (which computes the derived
    // Media.location). Pre-existing DaoMedia rows therefore deserialize with
    // location = None and the GEO index ends up massively under-populated.
    // Walk every media, derive the effective location with the same precedence
    // as Media.location — userDefined → deducted → original.location → first
    // bag with a location — and rewrite the row whenever the stored value
    // drifts.
//    val rematerializeMediaLocations =
//      collections.medias
//        .streamWithKeys()
//        .mapZIO { case (originalId, daoMedia) =>
//          for {
//            maybeOriginal <- collections.originals.fetch(originalId)
//            bagLocation   <- ZIO
//                               .foreach(daoMedia.bagId)(bagId => collections.bags.fetch(bagId))
//                               .map(_.flatten.iterator.flatMap(_.location).nextOption())
//            computed       = daoMedia.userDefinedLocation
//                               .orElse(daoMedia.deductedLocation)
//                               .orElse(maybeOriginal.flatMap(_.location))
//                               .orElse(bagLocation)
//                               .filter(l => l.latitude.doubleValue != 0d && l.longitude.doubleValue != 0d)
//            changed       <- if (computed == daoMedia.location) ZIO.succeed(0)
//                             else
//                               collections.medias
//                                 .upsertOverwrite(originalId, daoMedia.copy(location = computed))
//                                 .as(1)
//          } yield changed
//        }
//        .runFold(0L)(_ + _)

    (//rematerializeMediaLocations
      //.tap(count => ZIO.logInfo(s"daoMedia.location rematerialized for $count medias")) *>
      collections.medias.rebuildIndexes() *>
      ZIO.logInfo("medias indexes rebuilt !") *>
      collections.originals.rebuildIndexes() *>
      ZIO.logInfo("originals indexes rebuilt !") *>
      collections.detectedFaces.rebuildIndexes() *>
      ZIO.logInfo("detectedFaces indexes rebuilt !") *>
      rebuildPositional *>
      ZIO.logInfo("originalIdByPosition index rebuilt !"))
      .logError("Reindex failed")
      .mapError(err => ServiceDatabaseIssue(s"Reindex failed: $err"))
      .unit
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
      .filterNot(_.matches("^[-0-9]+$"))                // TODO add option to rules to ignore standalone numbers
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
      .map(_.media)
      .filter(_.original.store.id == storeId)
      .map(media => (media.keywords.toList ++ media.bag.toList.flatMap(_.keywords)).groupMapReduce(_.text)(_ => 1)(_ + _))
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
      .map(_.media)
      .filter(_.original.store.id == storeId)
      .map(media => media.copy(keywords = media.keywords.filterNot(_.text == keyword.text)))
      .flatMap(media => ZStream.fromIterable(media.bag))
      .map(bag => bag.copy(keywords = bag.keywords.filterNot(_.text == keyword.text)))
      .tap(bag =>
        bagUpdate(
          bag.id,
          name = bag.name,
          description = bag.description,
          location = bag.location,
          timestamp = bag.timestamp,
          coverOriginalId = bag.originalId,
          publishedOn = bag.publishedOn,
          keywords = bag.keywords
        )
      )
      .runDrain
      .mapError(err => ServiceDatabaseIssue(s"Couldn't delete keyword : $err"))
  }

  override def keywordRulesList(): IO[ServiceIssue, Chunk[KeywordRules]] = {
    collections.keywordRules
      .stream()
      .mapZIO(r => ZIO.attempt(r.transformInto[KeywordRules]))
      .mapError(err => ServiceDatabaseIssue(s"Couldn't collect keyword rules : $err"))
      .runCollect
  }

  override def keywordRulesGet(storeId: StoreId): IO[ServiceIssue, Option[KeywordRules]] = {
    collections.keywordRules
      .fetch(storeId)
      .flatMap(r => ZIO.attempt(r.map(_.transformInto[KeywordRules])))
      .mapError(err => ServiceDatabaseIssue(s"Couldn't get keyword rules : $err"))
  }

  override def keywordRulesUpsert(storeId: StoreId, rules: KeywordRules): IO[ServiceIssue, Unit] = {
    collections.keywordRules
      .upsert(storeId, _ => rules.transformInto[DaoKeywordRules])
      .mapError(err => ServiceDatabaseIssue(s"Couldn't create or update keyword rules : $err"))
      .unit
  }

  override def keywordRulesDelete(storeId: StoreId): IO[ServiceIssue, Unit] = {
    collections.keywordRules
      .delete(storeId)
      .mapError(err => ServiceDatabaseIssue(s"Couldn't delete keyword rule : $err"))
      .unit
  }

}

object MediaServiceLive {

  // -------------------------------------------------------------------------------------------------------------------
  def mapCodec[A, B](base: KeyCodec[A], to: A => B, from: B => A): KeyCodec[B] = new KeyCodec[B] {
    def encode(b: B): Array[Byte]                       = base.encode(from(b))
    def decode(b: ByteBuffer): Either[KeyCodecError, B] = base.decode(b).map(to)
    // Identical byte layout to the wrapped codec, so it keeps the same width and keyId.
    override def width: Option[Int]                     = base.width
    val keyId: KeyTypeId                                = base.keyId
  }

  given KeyCodec[OriginalId]  = mapCodec(summon[KeyCodec[UUID]], OriginalId.apply, _.asUUID)
  given KeyCodec[BagId]       = mapCodec(summon[KeyCodec[UUID]], BagId.apply, _.asUUID)
  given KeyCodec[StoreId]     = mapCodec(summon[KeyCodec[UUID]], StoreId.apply, _.asUUID)
  given KeyCodec[PortfolioId] = mapCodec(summon[KeyCodec[UUID]], PortfolioId.apply, _.asUUID)
  given KeyCodec[PersonId]    = mapCodec(summon[KeyCodec[ULID]], PersonId.apply, _.asULID)
  given KeyCodec[FaceId]      = mapCodec(summon[KeyCodec[ULID]], FaceId.apply, _.asULID)
  given KeyCodec[OwnerId]     = mapCodec(summon[KeyCodec[ULID]], OwnerId.apply, _.asULID)

  // -------------------------------------------------------------------------------------------------------------------
  private val originalsCollectionName            = "originals"
  private val statesCollectionName               = "states"
  private val bagsCollectionName                 = "bags"
  private val mediasCollectionName               = "medias"
  private val ownersCollectionName               = "owners"
  private val storesCollectionName               = "stores"
  private val keywordRulesCollectionName         = "keywordRules"
  private val classificationsCollectionName      = "classifications"
  private val detectedFacesCollectionName        = "detectedFaces"
  private val facesCollectionName                = "faces"
  private val detectedFaceFeaturesCollectionName = "detectedFaceFeatures"
  private val faceFeaturesCollectionName         = "faceFeatures"
  private val objectsCollectionName              = "objects"
  private val miniaturesCollectionName           = "miniatures"
  private val normalizedCollectionName           = "normalized"
  private val personsCollectionName              = "persons"
  private val portfoliosCollectionName           = "portfolios"
  private val portfolioAssetsCollectionName      = "portfolioAssets"

  private val allCollections = List(
    originalsCollectionName,
    statesCollectionName,
    bagsCollectionName,
    mediasCollectionName,
    ownersCollectionName,
    storesCollectionName,
    keywordRulesCollectionName,
    classificationsCollectionName,
    detectedFacesCollectionName,
    facesCollectionName,
    detectedFaceFeaturesCollectionName,
    faceFeaturesCollectionName,
    objectsCollectionName,
    miniaturesCollectionName,
    normalizedCollectionName,
    personsCollectionName,
    portfoliosCollectionName
  )

  def setupMediaServiceDatabase(lmdb: LMDB): ZIO[Any, LMDBIssues, MediaServiceDatabase] = for {
    // ----------------------------------------------------------------------------------------
    // INDEXES
    indexOriginalIdByTimestamp <- lmdb.indexCreate[(Instant, OriginalId), OriginalId]("originalIdByTimestamp", false)
    indexOriginalIdByPosition  <- lmdb.indexCreate[Long, OriginalId]("originalIdByPosition", false)
    indexOriginalIdByBagId     <- lmdb.indexCreate[BagId, (Instant, OriginalId)]("originalIdByBagId", false)
    indexFaceIdByPersonId      <- lmdb.indexCreate[PersonId, (Instant, FaceId)]("faceIdByPersonId", false)
    indexOriginalIdByStoreId   <- lmdb.indexCreate[StoreId, OriginalId]("originalIdByStoreId", false)
    indexOriginalIdByLocation  <- lmdb.indexCreate[GEOTools.Location, OriginalId]("originalIdByLocation", false)

    // ----------------------------------------------------------------------------------------
    // COLLECTIONS
    // Indexes are attached with `withDeclaredIndex`: the write path is the same as `withIndexFull`,
    // and the declared field paths are persisted in the index metadata so the zio-lmdb SQL engine
    // can serve WHERE/ORDER BY from the indexes. The declared paths must mirror the accessors.
    collectionOriginals            <- lmdb
                                        .collectionCreate[OriginalId, DaoOriginal](originalsCollectionName, false)
                                        .flatMap(
                                          _.withDeclaredIndex(indexOriginalIdByStoreId)(
                                            from = IdxKey.of(IdxKey.field("storeId")((_, original: DaoOriginal) => original.storeId)),
                                            to = IdxKey.of(IdxKey.primaryKey)
                                          )
                                        )
    collectionStates               <- lmdb
                                        .collectionCreate[OriginalId, DaoState](statesCollectionName, false)
    collectionBags                 <- lmdb
                                        .collectionCreate[BagId, DaoBag](bagsCollectionName, false)
    collectionMedias               <- lmdb
                                        .collectionCreate[OriginalId, DaoMedia](mediasCollectionName, false)
                                        .flatMap(
                                          _.withDeclaredIndex(indexOriginalIdByBagId)(
                                            from = IdxKey.of(IdxKey.fieldOpt("bagId")((_, media: DaoMedia) => media.bagId)),
                                            to = IdxKey.tuple(IdxKey.field("timestamp")((_, media: DaoMedia) => media.timestamp.toInstant), IdxKey.primaryKey)
                                          )
                                        )
                                        .flatMap(
                                          _.withDeclaredIndex(indexOriginalIdByTimestamp)(
                                            from = IdxKey.tuple(IdxKey.field("timestamp")((_, media: DaoMedia) => media.timestamp.toInstant), IdxKey.primaryKey),
                                            to = IdxKey.of(IdxKey.primaryKey)
                                          )
                                        )
                                        .map(
                                          // geo keys have no declarative planner support yet — kept as an opaque extractor
                                          _.withIndexFull(indexOriginalIdByLocation)((id, media) => media.location.map(l => GEOTools.Location(l.latitude.doubleValue, l.longitude.doubleValue) -> id).toList)
                                        )
    collectionOwners               <- lmdb.collectionCreate[OwnerId, DaoOwner](ownersCollectionName, false)
    collectionStores               <- lmdb.collectionCreate[StoreId, DaoStore](storesCollectionName, false)
    collectionKeywordRules         <- lmdb.collectionCreate[StoreId, DaoKeywordRules](keywordRulesCollectionName, false)
    collectionClassifications      <- lmdb.collectionCreate[OriginalId, DaoOriginalClassifications](classificationsCollectionName, false)
    collectionDetectedFaces        <- lmdb
                                        .collectionCreate[FaceId, DaoDetectedFace](detectedFacesCollectionName, false)
                                        .flatMap(
                                          _.withDeclaredIndex(indexFaceIdByPersonId)(
                                            from = IdxKey.of(
                                              IdxKey.coalesce("identifiedPersonId", "inferredIdentifiedPersonId")((_, face: DaoDetectedFace) =>
                                                face.identifiedPersonId.orElse(face.inferredIdentifiedPersonId)
                                              )
                                            ),
                                            to = IdxKey.tuple(IdxKey.field("timestamp")((_, face: DaoDetectedFace) => face.timestamp.toInstant), IdxKey.primaryKey)
                                          )
                                        )
    collectionOriginalFoundFaces   <- lmdb.collectionCreate[OriginalId, DaoOriginalFaces](facesCollectionName, false)
    collectionFaceFeatures         <- lmdb.collectionCreate[FaceId, DaoFaceFeatures](detectedFaceFeaturesCollectionName, false)
    collectionOriginalFaceFeatures <- lmdb.collectionCreate[OriginalId, DaoOriginalFaceFeatures](faceFeaturesCollectionName, false)
    collectionObjects              <- lmdb.collectionCreate[OriginalId, DaoOriginalDetectedObjects](objectsCollectionName, false)
    collectionMiniatures           <- lmdb.collectionCreate[OriginalId, DaoOriginalMiniatures](miniaturesCollectionName, false)
    collectionNormalized           <- lmdb.collectionCreate[OriginalId, DaoOriginalNormalized](normalizedCollectionName, false)
    collectionPersons              <- lmdb.collectionCreate[PersonId, DaoPerson](personsCollectionName, false)
    collectionPortfolios           <- lmdb.collectionCreate[PortfolioId, DaoPortfolio](portfoliosCollectionName, false)
    collectionPortfolioAssets      <- lmdb.multiCreate[PortfolioId, DaoAsset](portfolioAssetsCollectionName, false)
    collections                     = MediaServiceDatabase(
                                        originalIdByTimestamp = indexOriginalIdByTimestamp,
                                        originalIdByPosition = indexOriginalIdByPosition,
                                        originalIdByBagId = indexOriginalIdByBagId,
                                        faceIdByPersonId = indexFaceIdByPersonId,
                                        originalIdByStoreId = indexOriginalIdByStoreId,
                                        originalIdByLocation = indexOriginalIdByLocation,
                                        originals = collectionOriginals,
                                        states = collectionStates,
                                        bags = collectionBags,
                                        medias = collectionMedias,
                                        owners = collectionOwners,
                                        stores = collectionStores,
                                        keywordRules = collectionKeywordRules,
                                        classifications = collectionClassifications,
                                        detectedFaces = collectionDetectedFaces,
                                        originalFaces = collectionOriginalFoundFaces,
                                        faceFeatures = collectionFaceFeatures,
                                        originalFaceFeatures = collectionOriginalFaceFeatures,
                                        objects = collectionObjects,
                                        miniatures = collectionMiniatures,
                                        normalized = collectionNormalized,
                                        persons = collectionPersons,
                                        portfolios = collectionPortfolios,
                                        portfolioAssets = collectionPortfolioAssets
                                      )
  } yield collections

  def setupProcessors() = for {
    classificationProcessor <- ClassificationProcessor.allocate().memoize
    facesProcessor          <- FacesProcessor.allocate().memoize
    featuresProcessor       <- FaceFeaturesProcessor.allocate().memoize
    objectsProcessor        <- ObjectsDetectionProcessor.allocate().memoize
    processors               = MediaServiceProcessors(
                                 classifications = classificationProcessor,
                                 faces = facesProcessor,
                                 faceFeatures = featuresProcessor,
                                 objects = objectsProcessor
                               )

  } yield processors

  // Backfill the positional index on first run after upgrade: if it's empty but
  // medias exist, walk them once and assign sequential positions. After this,
  // new medias get appended on creation by assignNextPosition.
  private def backfillPositionalIndex(collections: MediaServiceDatabase): IO[LMDBIssues, Unit] = {
    collections.originalIdByPosition.readWrite { ops =>
      ops.head().flatMap {
        case Some(_) => ZIO.unit
        case None    =>
          collections.medias
            .streamWithKeys()
            .zipWithIndex
            .runForeach { case ((originalId, _), idx) => ops.index(idx, originalId) }
      }
    }.unit
  }

  def setup(lmdb: LMDB, search: SearchService): IO[LMDBIssues | CoreIssue, MediaService] = for {
    _                          <- ZIO.foreachDiscard(allCollections)(col => lmdb.collectionAllocate(col).ignore)
    mediaServiceDatabase       <- setupMediaServiceDatabase(lmdb)
    _                          <- backfillPositionalIndex(mediaServiceDatabase)
    processors                 <- setupProcessors()
    synchronizeStatusReference <- Ref.make(SynchronizeStatus.empty)
    synchronizeFiberReference  <- Ref.make(Option.empty[Fiber[ServiceIssue, Unit]])
  } yield new MediaServiceLive(
    lmdb,
    search,
    mediaServiceDatabase,
    processors,
    // ------------------------
    synchronizeStatusReference,
    synchronizeFiberReference
  )

  // -------------------------------------------------------------------------------------------------------------------

}
