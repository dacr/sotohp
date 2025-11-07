package fr.janalyse.sotohp.api

import com.typesafe.config.ConfigFactory
import fr.janalyse.sotohp.processor.model.FaceId
import fr.janalyse.sotohp.search.SearchService
import zio.*
import zio.json.*
import sttp.apispec.openapi.Info
import sttp.model.{Header, HeaderNames, MediaType, StatusCode}
import sttp.tapir.generic.auto.*
import sttp.tapir.json.zio.*
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import sttp.tapir.swagger.bundle.SwaggerInterpreter
import sttp.tapir.ztapir.*
//import zio.logging.backend.SLF4J
import zio.logging.LogFormat
import fr.janalyse.sotohp.service.{MediaService, ServiceStreamIssue}
import fr.janalyse.sotohp.api.protocol.{*, given}
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.service.model.SynchronizeAction
import fr.janalyse.sotohp.service.model.SynchronizeAction.Start
import sttp.capabilities.zio.ZioStreams
import sttp.model.headers.CacheDirective
import sttp.tapir.{Codec, CodecFormat, EndpointInput, Schema}
import sttp.tapir.files.staticFilesGetServerEndpoint
import zio.config.typesafe.TypesafeConfigProvider
import zio.http.Server
import zio.lmdb.LMDB

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import io.scalaland.chimney.dsl.*
import sttp.tapir.Codec.PlainCodec
import zio.http.Server.Config.ResponseCompressionConfig
import zio.stream.{ZPipeline, ZStream}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.time.OffsetDateTime
import java.util.UUID

object ApiApp extends ZIOAppDefault {

  type ApiEnv = MediaService

  // -------------------------------------------------------------------------------------------------------------------

  case object NdJson extends CodecFormat {
    override val mediaType: MediaType = MediaType.parse("application/x-ndjson").toOption.get
    override def toString: String     = "ndjson"
  }

  // -------------------------------------------------------------------------------------------------------------------

  val configProvider      = TypesafeConfigProvider.fromTypesafeConfig(com.typesafe.config.ConfigFactory.load())
  val configProviderLayer = Runtime.setConfigProvider(configProvider)

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] = {
    // val fmt = LogFormat.level |-| LogFormat.annotations |-| LogFormat.line
    // val fmt     = LogFormat.annotations |-| LogFormat.line
    // val logging = Runtime.removeDefaultLoggers >>> SLF4J.slf4j(format = fmt)
    val logging = zio.logging.slf4j.bridge.Slf4jBridge.initialize

    logging
      ++ configProviderLayer
      ++ Runtime.enableAutoBlockingExecutor // TODO identify where some blocking operation markers have been forgotten
  }

  // -------------------------------------------------------------------------------------------------------------------
  val userAgent = header[Option[String]]("User-Agent").schema(_.hidden(true))

  val statusForApiInvalidRequestError = oneOfVariant(StatusCode.BadRequest, jsonBody[ApiInvalidOrMissingInput].description("Invalid identifier provided"))
  val statusForApiInternalError       = oneOfVariant(StatusCode.InternalServerError, jsonBody[ApiInternalError].description("Something went wrong with the backend"))
  val statusForApiResourceNotFound    = oneOfVariant(StatusCode.NotFound, jsonBody[ApiResourceNotFound].description("Couldn't find the request resource"))

  // -------------------------------------------------------------------------------------------------------------------
  val systemEndpoint                          = endpoint.in("api").in("system").tag("System")
  val adminEndpoint                           = endpoint.in("api").in("admin").tag("Admin")
  def stateEndpoint(plurial: Boolean = false) = endpoint.in("api").in("state" + (if (plurial) "s" else "")).tag("State")
  def mediaEndpoint(plurial: Boolean = false) = endpoint.in("api").in("media" + (if (plurial) "s" else "")).tag("Media")
  def storeEndpoint(plurial: Boolean = false) = endpoint.in("api").in("store" + (if (plurial) "s" else "")).tag("Store")
  def ownerEndpoint(plurial: Boolean = false) = endpoint.in("api").in("owner" + (if (plurial) "s" else "")).tag("Owner")
  def eventEndpoint(plurial: Boolean = false) = endpoint.in("api").in("event" + (if (plurial) "s" else "")).tag("Event")
  def faceEndpoint(plurial: Boolean = false)  = endpoint.in("api").in("face" + (if (plurial) "s" else "")).tag("Face")

  // -------------------------------------------------------------------------------------------------------------------
  def extractOwnerId(rawOwnerId: String) =
    ZIO
      .attempt(OwnerId.fromString(rawOwnerId))
      .mapError(err => ApiInvalidOrMissingInput("Invalid owner identifier"))

  def extractFaceId(rawFaceId: String) =
    ZIO
      .attempt(FaceId.fromString(rawFaceId))
      .mapError(err => ApiInvalidOrMissingInput("Invalid face identifier"))

  def extractMediaAccessKey(rawMediaAccessKey: String) =
    ZIO
      .attempt(MediaAccessKey.apply(rawMediaAccessKey))
      .mapError(err => ApiInvalidOrMissingInput("Invalid media access key"))

  def extractEventId(rawEventId: String) =
    ZIO
      .attempt(EventId(java.util.UUID.fromString(rawEventId)))
      .mapError(err => ApiInvalidOrMissingInput("Invalid event identifier"))

  def extractStoreId(rawStoreId: String) =
    ZIO
      .attempt(StoreId.fromString(rawStoreId))
      .mapError(err => ApiInvalidOrMissingInput("Invalid store identifier"))

  def extractOriginalId(rawOriginalId: UUID) =
    ZIO
      .attempt(OriginalId(rawOriginalId))
      .mapError(err => ApiInvalidOrMissingInput("Invalid original identifier"))

  // -------------------------------------------------------------------------------------------------------------------

  def stateGetLogic(originalId: OriginalId): ZIO[ApiEnv, ApiIssue, ApiState] = {
    for {
      state   <- MediaService
                   .stateGet(originalId)
                   .logError("Couldn't get state")
                   .mapError(err => ApiInternalError("Couldn't get state"))
                   .someOrFail(ApiResourceNotFound("Couldn't find state"))
      taoState = state.transformInto[ApiState]
    } yield taoState
  }

  val stateGetEndpoint =
    stateEndpoint()
      .name("Get original state")
      .summary("Get original/media state information for the given original identifier")
      .description("Also get the related media access key for the selected original")
      .get
      .in(path[UUID]("originalId"))
      .out(jsonBody[ApiState])
      .errorOut(oneOf(statusForApiInternalError, statusForApiResourceNotFound, statusForApiInvalidRequestError))
      .zServerLogic[ApiEnv](rawOriginalId =>
        extractOriginalId(rawOriginalId)
          .flatMap(originalId => stateGetLogic(originalId))
      )

  // -------------------------------------------------------------------------------------------------------------------

  def ownerCreateLogic(toCreate: ApiOwnerCreate): ZIO[ApiEnv, ApiInternalError, ApiOwner] = {
    for {
      owner   <- MediaService
                   .ownerCreate(None, toCreate.firstName, toCreate.lastName, toCreate.birthDate)
                   .mapError(err => ApiInternalError("Couldn't create owner"))
      taoOwner = owner.transformInto[ApiOwner]
    } yield taoOwner
  }

  val ownerCreateEndpoint =
    ownerEndpoint()
      .name("Create an owner")
      .summary("Create a new media owner")
      .post
      .in(jsonBody[ApiOwnerCreate])
      .out(jsonBody[ApiOwner])
      .errorOut(oneOf(statusForApiInternalError))
      .zServerLogic[ApiEnv](toCreate => ownerCreateLogic(toCreate))

  def ownerGetLogic(ownerId: OwnerId): ZIO[ApiEnv, ApiIssue, ApiOwner] = {
    for {
      owner   <- MediaService
                   .ownerGet(ownerId)
                   .logError("Couldn't get owner")
                   .mapError(err => ApiInternalError("Couldn't get owner"))
                   .someOrFail(ApiResourceNotFound("Couldn't find owner"))
      taoOwner = owner.transformInto[ApiOwner]
    } yield taoOwner
  }

  val ownerGetEndpoint =
    ownerEndpoint()
      .name("Get owner")
      .summary("Get all owner information for the given owner identifier")
      .get
      .in(path[String]("ownerId"))
      .out(jsonBody[ApiOwner])
      .errorOut(oneOf(statusForApiInternalError, statusForApiResourceNotFound, statusForApiInvalidRequestError))
      .zServerLogic[ApiEnv](rawOwnerId =>
        extractOwnerId(rawOwnerId)
          .flatMap(ownerId => ownerGetLogic(ownerId))
      )

  def ownerUpdateLogic(ownerId: OwnerId, toUpdate: ApiOwnerUpdate): ZIO[ApiEnv, ApiIssue, Unit] = {
    val logic = for {
      owner <- MediaService
                 .ownerGet(ownerId)
                 .logError("Couldn't get owner")
                 .mapError(err => ApiInternalError("Couldn't get owner"))
                 .someOrFail(ApiResourceNotFound("Couldn't find owner"))
      _     <- MediaService
                 .ownerUpdate(
                   ownerId,
                   firstName = toUpdate.firstName,
                   lastName = toUpdate.lastName,
                   birthDate = toUpdate.birthDate,
                   coverOriginalId = owner.originalId
                 )
                 .logError("Couldn't update owner")
                 .mapError(err => ApiInternalError("Couldn't update owner"))
    } yield ()

    logic
  }

  val ownerUpdateEndpoint =
    ownerEndpoint()
      .name("Update owner")
      .summary("Update owner configuration for the given owner identifier")
      .put
      .in(path[String]("ownerId"))
      .in(jsonBody[ApiOwnerUpdate]) // TODO security and regex fields
      .errorOut(oneOf(statusForApiInternalError, statusForApiResourceNotFound, statusForApiInvalidRequestError))
      .zServerLogic[ApiEnv]((rawOwnerId, toUpdate) =>
        extractOwnerId(rawOwnerId)
          .flatMap(ownerId => ownerUpdateLogic(ownerId, toUpdate))
      )

  val ownerUpdateCoverEndpoint =
    ownerEndpoint()
      .name("Update owner image cover")
      .summary("Update owner image cover for the given owner and original identifiers")
      .put
      .in(path[String]("ownerId"))
      .in("cover")
      .in(path[String]("mediaAccessKey"))
      .errorOut(oneOf(statusForApiInternalError, statusForApiResourceNotFound, statusForApiInvalidRequestError))
      .zServerLogic[ApiEnv]((rawOwnerId, rawMediaAccessKey) =>
        for {
          ownerId        <- extractOwnerId(rawOwnerId)
          mediaAccessKey <- extractMediaAccessKey(rawMediaAccessKey)
          owner          <- ownerGetLogic(ownerId)
          media          <- mediaGetLogic(mediaAccessKey)
          _              <- MediaService
                              .ownerUpdate(
                                ownerId = ownerId,
                                firstName = owner.firstName,
                                lastName = owner.lastName,
                                birthDate = owner.birthDate,
                                coverOriginalId = Some(media.original.id)
                              )
                              .logError("Couldn't update owner")
                              .mapError(err => ApiInternalError("Couldn't update owner"))

        } yield ()
      )

  val ownerListLogic: ZStream[MediaService, Throwable, ApiOwner] = {
    MediaService
      .ownerList()
      .map(owner => owner.transformInto[ApiOwner])
      .mapError(err => ApiInternalError("Couldn't list owners"))
  }

  val ownerListEndpoint =
    ownerEndpoint(true)
      .name("List owners")
      .summary("Stream all defined owners")
      .get
      .out(
        streamBody(ZioStreams)(ApiOwner.apiOwnerSchema, NdJson, Some(StandardCharsets.UTF_8))
          .description("NDJSON (one Owner JSON object per line)")
      ) // TODO how to provide information about the fact we want NDJSON output of ApiOwner ?
      .errorOut(oneOf(statusForApiInternalError))
      .zServerLogic[ApiEnv](_ =>
        for {
          ms        <- ZIO.service[MediaService]
          byteStream = ownerListLogic
                         .map(_.toJson)
                         .intersperse("\n")
                         .via(ZPipeline.utf8Encode)
                         .provideEnvironment(ZEnvironment(ms))
        } yield byteStream
      )

  // -------------------------------------------------------------------------------------------------------------------

  def storeCreateLogic(toCreate: ApiStoreCreate): ZIO[ApiEnv, ApiInternalError, ApiStore] = {
    for {
      store   <- MediaService
                   .storeCreate(
                     None,
                     name = toCreate.name,
                     ownerId = toCreate.ownerId,
                     baseDirectory = toCreate.baseDirectory,
                     includeMask = toCreate.includeMask,
                     ignoreMask = toCreate.ignoreMask
                   )
                   .mapError(err => ApiInternalError("Couldn't create store"))
      taoStore = store.transformInto[ApiStore]
    } yield taoStore
  }

  val storeCreateEndpoint =
    storeEndpoint()
      .name("Create an store")
      .summary("Create a new media store")
      .post
      .in(jsonBody[ApiStoreCreate])
      .out(jsonBody[ApiStore])
      .errorOut(oneOf(statusForApiInternalError))
      .zServerLogic[ApiEnv](toCreate => storeCreateLogic(toCreate))

  def storeGetLogic(storeId: StoreId): ZIO[ApiEnv, ApiIssue, ApiStore] = {
    val logic = for {
      store   <- MediaService
                   .storeGet(storeId)
                   .logError("Couldn't get store")
                   .mapError(err => ApiInternalError("Couldn't get store"))
                   .someOrFail(ApiResourceNotFound("Couldn't find store"))
      taoStore = store.transformInto[ApiStore]
    } yield taoStore

    logic
  }

  val storeGetEndpoint =
    storeEndpoint()
      .name("Get store")
      .summary("Get all store information for the given store identifier")
      .get
      .in(path[String]("storeId"))
      .out(jsonBody[ApiStore])
      .errorOut(oneOf(statusForApiInternalError, statusForApiResourceNotFound, statusForApiInvalidRequestError))
      .zServerLogic[ApiEnv](rawStoreId =>
        extractStoreId(rawStoreId)
          .flatMap(storeId => storeGetLogic(storeId))
      )

  def storeUpdateLogic(storeId: StoreId, toUpdate: ApiStoreUpdate): ZIO[ApiEnv, ApiIssue, Unit] = {
    val logic = for {
      store <- MediaService
                 .storeGet(storeId)
                 .logError("Couldn't get store")
                 .mapError(err => ApiInternalError("Couldn't get store"))
                 .someOrFail(ApiResourceNotFound("Couldn't find store"))
      _     <- MediaService
                 .storeUpdate(storeId, name = toUpdate.name, baseDirectory = toUpdate.baseDirectory, includeMask = toUpdate.includeMask, ignoreMask = toUpdate.ignoreMask)
                 .logError("Couldn't update store")
                 .mapError(err => ApiInternalError("Couldn't update store"))
    } yield ()

    logic
  }

  val storeUpdateEndpoint =
    storeEndpoint()
      .name("Update store")
      .summary("Update store configuration for the given store identifier")
      .put
      .in(path[String]("storeId"))
      .in(jsonBody[ApiStoreUpdate]) // TODO security and regex fields
      .errorOut(oneOf(statusForApiInternalError, statusForApiResourceNotFound, statusForApiInvalidRequestError))
      .zServerLogic[ApiEnv]((rawStoreId, toUpdate) =>
        extractStoreId(rawStoreId)
          .flatMap(storeId => storeUpdateLogic(storeId, toUpdate))
      )

  val storeListLogic: ZStream[MediaService, Throwable, ApiStore] = {
    MediaService
      .storeList()
      .map(store => store.transformInto[ApiStore])
      .mapError(err => ApiInternalError("Couldn't list stores"))
  }

  val storeListEndpoint =
    storeEndpoint(true)
      .name("List stores")
      .summary("Stream all defined stores")
      .get
      .out(
        streamBody(ZioStreams)(ApiStore.apiStoreSchema, NdJson, Some(StandardCharsets.UTF_8))
          .description("NDJSON (one Store JSON object per line)")
          // .schema(summon[Schema[List[ApiStore]]])
      ) // TODO how to provide information about the fact we want NDJSON output of ApiStore ?
      .errorOut(oneOf(statusForApiInternalError))
      .zServerLogic[ApiEnv](_ =>
        for {
          ms        <- ZIO.service[MediaService]
          byteStream = storeListLogic
                         .map(_.toJson)
                         .intersperse("\n")
                         .via(ZPipeline.utf8Encode)
                         .provideEnvironment(ZEnvironment(ms))
        } yield byteStream
      )
  // -------------------------------------------------------------------------------------------------------------------

  def mediaGetLogic(accessKey: MediaAccessKey): ZIO[ApiEnv, ApiIssue, ApiMedia] = {
    val logic = for {
      media   <- MediaService
                   .mediaGet(accessKey)
                   .logError("Couldn't get media")
                   .mapError(err => ApiInternalError("Couldn't get media"))
                   .someOrFail(ApiResourceNotFound("Couldn't find media"))
      taoMedia = media.transformInto[ApiMedia](using ApiMedia.transformer)
    } yield taoMedia

    logic
  }

  val mediaGetEndpoint =
    mediaEndpoint()
      .name("Get media")
      .summary("Get all media information for the given media access key")
      .get
      .in(path[String]("mediaAccessKey"))
      .out(jsonBody[ApiMedia])
      .errorOut(oneOf(statusForApiInternalError, statusForApiResourceNotFound, statusForApiInvalidRequestError))
      .zServerLogic[ApiEnv](rawMediaAccessKey =>
        extractMediaAccessKey(rawMediaAccessKey)
          .flatMap(mediaAccessKey => mediaGetLogic(mediaAccessKey))
      )

  // -------------------------------------------------------------------------------------------------------------------
  def mediaUpdateLogic(accessKey: MediaAccessKey, toUpdate: ApiMediaUpdate): ZIO[ApiEnv, ApiIssue, Unit] = {
    val logic = for {
      media <- MediaService
                 .mediaGet(accessKey)
                 .logError("Couldn't get media")
                 .mapError(err => ApiInternalError("Couldn't get media"))
                 .someOrFail(ApiResourceNotFound("Couldn't find media"))
      _     <- MediaService
                 .mediaUpdate(
                   media.accessKey,
                   media.copy(
                     description = toUpdate.description,
                     starred = toUpdate.starred,
                     keywords = toUpdate.keywords,
                     shootDateTime = toUpdate.shootDateTime,
                     userDefinedLocation = toUpdate.userDefinedLocation.transformInto[Option[Location]]
                   )
                 )
                 .logError("Couldn't update media")
                 .mapError(err => ApiInternalError("Couldn't update media"))
    } yield ()

    logic
  }

  val mediaUpdateEndpoint =
    mediaEndpoint()
      .name("Update media")
      .summary("Update media information for the given media identifier")
      .put
      .in(path[String]("mediaAccessKey"))
      .in(jsonBody[ApiMediaUpdate])
      .errorOut(oneOf(statusForApiInternalError, statusForApiResourceNotFound, statusForApiInvalidRequestError))
      .zServerLogic[ApiEnv]((rawMediaAccessKey, toUpdate) =>
        extractMediaAccessKey(rawMediaAccessKey)
          .flatMap(eventId => mediaUpdateLogic(eventId, toUpdate))
      )

  // -------------------------------------------------------------------------------------------------------------------
  def mediaUpdateStarredLogic(accessKey: MediaAccessKey, state: Boolean): ZIO[ApiEnv, ApiIssue, Unit] = {
    for {
      media <- MediaService
                 .mediaGet(accessKey)
                 .logError("Couldn't get media")
                 .mapError(err => ApiInternalError("Couldn't get media"))
                 .someOrFail(ApiResourceNotFound("Couldn't find media"))
      _     <- MediaService
                 .mediaUpdate(media.accessKey, media.copy(starred = Starred(state)))
                 .logError("Couldn't update media")
                 .mapError(err => ApiInternalError("Couldn't update media"))
    } yield ()
  }

  val mediaUpdateStarredEndpoint =
    mediaEndpoint()
      .name("Update media")
      .summary("Update media starred state for the given media identifier")
      .put
      .in(path[String]("mediaAccessKey"))
      .in("starred")
      .in(query[Boolean]("state"))
      .errorOut(oneOf(statusForApiInternalError, statusForApiResourceNotFound, statusForApiInvalidRequestError))
      .zServerLogic[ApiEnv]((rawMediaAccessKey, state) =>
        extractMediaAccessKey(rawMediaAccessKey)
          .flatMap(mediaAccessKey => mediaUpdateStarredLogic(mediaAccessKey, state))
      )
  // -------------------------------------------------------------------------------------------------------------------

  enum WhichMedia {
    case Original
    case Normalized
    case Miniature
  }

  def mediaGetImageBytesStream(accessKey: MediaAccessKey, whichMedia: WhichMedia): ZIO[ApiEnv, ApiIssue, (Media, ZStream[ApiEnv, ApiInternalError, Byte])] = {
    val logic = for {
      media           <- MediaService
                           .mediaGet(accessKey)
                           .logError("Couldn't get media")
                           .mapError(err => ApiInternalError("Couldn't get media"))
                           .someOrFail(ApiResourceNotFound("Couldn't find media"))
      imageBytesStream = whichMedia match {
                           case WhichMedia.Original   => MediaService.mediaOriginalRead(media.accessKey)
                           case WhichMedia.Normalized => MediaService.mediaNormalizedRead(media.accessKey)
                           case WhichMedia.Miniature  => MediaService.mediaMiniatureRead(media.accessKey)
                         }
    } yield (media, imageBytesStream.mapError(err => ApiInternalError("Couldn't read media")))

    logic
  }

  def mediaGetImageBytesLogic(rawMediaAccessKey: String, whichMedia: WhichMedia) = {
    for {
      mediaKey            <- extractMediaAccessKey(rawMediaAccessKey)
      (media, byteStream) <- mediaGetImageBytesStream(mediaKey, whichMedia)
      ms                  <- ZIO.service[MediaService]
      httpStream           = byteStream.provideEnvironment(ZEnvironment(ms))
    } yield (MediaType.ImageJpeg.toString, httpStream)
  }

  // -------------------------------------------------------------------------------------------------------------------
  val mediaContentGetOriginalEndpoint =
    mediaEndpoint()
      .name("Get media original size image")
      .summary("Get media original size image content")
      .get
      .in(path[String]("mediaAccessKey"))
      .in("content" / "original")
      .out(header[String]("Content-Type"))
      .out(streamBinaryBody(ZioStreams)(CodecFormat.OctetStream()))
      .errorOut(oneOf(statusForApiInternalError, statusForApiResourceNotFound, statusForApiInvalidRequestError))
      .zServerLogic[ApiEnv](rawMediaAccessKey => mediaGetImageBytesLogic(rawMediaAccessKey, WhichMedia.Original))

  // -------------------------------------------------------------------------------------------------------------------

  val mediaContentGetNormalizedEndpoint =
    mediaEndpoint()
      .name("Get media normalized image")
      .summary("Get media normalized image content")
      .get
      .in(path[String]("mediaAccessKey"))
      .in("content" / "normalized")
      .out(header[String]("Content-Type"))
      .out(streamBinaryBody(ZioStreams)(CodecFormat.OctetStream()))
      .errorOut(oneOf(statusForApiInternalError, statusForApiResourceNotFound, statusForApiInvalidRequestError))
      .zServerLogic[ApiEnv](rawMediaAccessKey => mediaGetImageBytesLogic(rawMediaAccessKey, WhichMedia.Normalized))

  // -------------------------------------------------------------------------------------------------------------------

  val mediaContentGetMiniatureEndpoint =
    mediaEndpoint()
      .name("Get media miniature image")
      .summary("Get media miniature image content")
      .get
      .in(path[String]("mediaAccessKey"))
      .in("content" / "miniature")
      .out(header[String]("Content-Type"))
      .out(streamBinaryBody(ZioStreams)(CodecFormat.OctetStream()))
      .errorOut(oneOf(statusForApiInternalError, statusForApiResourceNotFound, statusForApiInvalidRequestError))
      .zServerLogic[ApiEnv](rawMediaAccessKey => mediaGetImageBytesLogic(rawMediaAccessKey, WhichMedia.Miniature))

  // -------------------------------------------------------------------------------------------------------------------

  def mediaListLogic(filterHasLocation: Option[Boolean]): ZStream[MediaService, Throwable, ApiMedia] = {
    MediaService
      .mediaList()
      .filter(media => filterHasLocation.isEmpty || media.location.isDefined == filterHasLocation.get)
      .map(media => media.transformInto[ApiMedia](using ApiMedia.transformer))
      .mapError(err => ApiInternalError("Couldn't list medias"))
  }

  val mediaListEndpoint =
    mediaEndpoint(true)
      .name("List medias")
      .summary("Stream all defined medias")
      .in(query[Option[Boolean]]("filterHasLocation"))
      .get
      .out(
        streamBody(ZioStreams)(ApiMedia.apiMediaSchema, NdJson, Some(StandardCharsets.UTF_8))
          .description("NDJSON (one Store JSON object per line)")
          // .schema(summon[Schema[List[ApiMedia]]])
      ) // TODO how to provide information about the fact we want NDJSON output of ApiMedia ?
      .errorOut(oneOf(statusForApiInternalError))
      .zServerLogic[ApiEnv]((filterHasLocation) =>
        for {
          ms        <- ZIO.service[MediaService]
          byteStream = mediaListLogic(filterHasLocation)
                         .map(_.toJson)
                         .intersperse("\n")
                         .via(ZPipeline.utf8Encode)
                         .provideEnvironment(ZEnvironment(ms))
        } yield byteStream
      )

  // -------------------------------------------------------------------------------------------------------------------
  enum MediaSelector {
    case random
    case first
    case previous
    case next
    case last
  }

  object MediaSelector {
    given PlainCodec[MediaSelector] = Codec.derivedEnumeration[String, MediaSelector].defaultStringBased
  }

  val mediaSelectRandomLogic = for {
    count <- MediaService
               .originalCount()
               .mapError(err => ApiInternalError("Couldn't get medias count"))
    index <- Random.nextLongBounded(count)
    media <- MediaService
               .mediaGetAt(index)
               .some // TODO Remember mediaGetAt is not performance optimal due to the nature of lmdb
               .mapError(err => ApiInternalError("Couldn't get a random media"))
  } yield media

  val mediaSelectFirstLogic = {
    MediaService
      .mediaFirst()
      .some
      .mapError(err => ApiResourceNotFound("Couldn't find first media"))
  }

  val mediaSelectLastLogic = {
    MediaService
      .mediaLast()
      .some
      .mapError(err => ApiResourceNotFound("Couldn't find last media"))
  }

  def mediaSelectPreviousLogic(nearKey: MediaAccessKey) = {
    MediaService
      .mediaPrevious(nearKey)
      .some
      .mapError(err => ApiResourceNotFound("Couldn't find previous media"))
  }

  def mediaSelectNextLogic(nearKey: MediaAccessKey) = {
    MediaService
      .mediaNext(nearKey)
      .some
      .mapError(err => ApiResourceNotFound("Couldn't find next media"))
  }

  val mediaSelectEndpoint =
    mediaEndpoint()
      .name("Get media information")
      .summary("Get information from a random, first, last, previous of, or next to media")
      .get
      .in(query[MediaSelector]("select"))
      .in(query[Option[String]]("referenceMediaAccessKey").description("previous or next media from the provided a reference media"))
      .in(query[Option[OffsetDateTime]]("referenceMediaTimestamp").description("previous or next media from the provided timestamp"))
      .out(jsonBody[ApiMedia])
      .errorOut(oneOf(statusForApiInternalError, statusForApiResourceNotFound))
      .zServerLogic[ApiEnv]((selectCriteria, mayBeRawKey, mayBeMediaTimestamp) =>
        for {
          nearKey <- ZIO
                       .attempt(
                         mayBeRawKey
                           .map(rawKey => MediaAccessKey(rawKey))
                           .orElse(mayBeMediaTimestamp.map(timestamp => MediaAccessKey(timestamp)))
                       )
                       .mapError(err => ApiInvalidOrMissingInput("Invalid media access key"))
          media   <- selectCriteria match {
                       case MediaSelector.random                        => mediaSelectRandomLogic
                       case MediaSelector.first                         => mediaSelectFirstLogic
                       case MediaSelector.previous if nearKey.isDefined => mediaSelectPreviousLogic(nearKey.get)
                       case MediaSelector.previous                      => ZIO.fail(ApiInvalidOrMissingInput("Missing required referenceMediaAccessKey parameter"))
                       case MediaSelector.next if nearKey.isDefined     => mediaSelectNextLogic(nearKey.get)
                       case MediaSelector.next                          => ZIO.fail(ApiInvalidOrMissingInput("Missing required referenceMediaAccessKey parameter"))
                       case MediaSelector.last                          => mediaSelectLastLogic
                     }
          taoMedia = media.transformInto[ApiMedia](using ApiMedia.transformer)
        } yield taoMedia
      )

  // -------------------------------------------------------------------------------------------------------------------

  def mediaFacesGetLogic(accessKey: MediaAccessKey): ZIO[ApiEnv, ApiIssue, ApiOriginalFaces] = {
    val logic = for {
      media           <- mediaGetLogic(accessKey)
      originalFaces   <- MediaService
                           .originalFaces(media.original.id)
                           .logError("Couldn't get media faces")
                           .mapError(err => ApiInternalError("Couldn't get media faces"))
                           .someOrFail(ApiResourceNotFound("Couldn't find media faces"))
      taoOriginalFaces = originalFaces.transformInto[ApiOriginalFaces](using ApiOriginalFaces.apiOriginalFacesTransformer)
    } yield taoOriginalFaces

    logic
  }

  val mediaFacesGetEndpoint =
    mediaEndpoint()
      .name("Get media faces")
      .summary("Get media identified people faces for the given media access key")
      .get
      .in(path[String]("mediaAccessKey"))
      .out(jsonBody[ApiOriginalFaces])
      .errorOut(oneOf(statusForApiInternalError, statusForApiResourceNotFound, statusForApiInvalidRequestError))
      .zServerLogic[ApiEnv](rawMediaAccessKey =>
        extractMediaAccessKey(rawMediaAccessKey)
          .flatMap(mediaAccessKey => mediaFacesGetLogic(mediaAccessKey))
      )

  // -------------------------------------------------------------------------------------------------------------------

  val faceListLogic: ZStream[MediaService, Throwable, ApiDetectedFace] = {
    MediaService
      .faceList()
      .map(face => face.transformInto[ApiDetectedFace])
      .mapError(err => ApiInternalError("Couldn't list detected faces"))
  }

  val faceListEndpoint =
    faceEndpoint(true)
      .name("List faces")
      .summary("Stream people faces")
      .get
      .out(
        streamBody(ZioStreams)(ApiDetectedFace.apiDetectedFaceSchema, NdJson, Some(StandardCharsets.UTF_8))
          .description("NDJSON (one Store JSON object per line)")
          // .schema(summon[Schema[List[ApiDetectedFace]]])
      ) // TODO how to provide information about the fact we want NDJSON output of ApiDetectedFace ?
      .errorOut(oneOf(statusForApiInternalError))
      .zServerLogic[ApiEnv](_ =>
        for {
          ms        <- ZIO.service[MediaService]
          byteStream = faceListLogic
                         .map(_.toJson)
                         .intersperse("\n")
                         .via(ZPipeline.utf8Encode)
                         .provideEnvironment(ZEnvironment(ms))
        } yield byteStream
      )

  def faceGetLogic(faceId: FaceId): ZIO[ApiEnv, ApiIssue, ApiDetectedFace] = {
    val logic = for {
      face   <- MediaService
                  .faceGet(faceId)
                  .logError("Couldn't get face")
                  .mapError(err => ApiInternalError("Couldn't get face"))
                  .someOrFail(ApiResourceNotFound("Couldn't find face"))
      taoFace = face.transformInto[ApiDetectedFace]
    } yield taoFace

    logic
  }

  val faceGetEndpoint =
    faceEndpoint()
      .name("Get face")
      .summary("Get all face information for the given face identifier")
      .get
      .in(path[String]("faceId"))
      .out(jsonBody[ApiDetectedFace])
      .errorOut(oneOf(statusForApiInternalError, statusForApiResourceNotFound, statusForApiInvalidRequestError))
      .zServerLogic[ApiEnv](rawFaceId =>
        extractFaceId(rawFaceId)
          .flatMap(id => faceGetLogic(id))
      )

  def faceGetImageBytesLogic(faceId: FaceId) = {
    val byteStream = MediaService.faceRead(faceId)
    for {
      ms        <- ZIO.service[MediaService]
      httpStream = byteStream.provideEnvironment(ZEnvironment(ms))
    } yield (MediaType.ImageJpeg.toString, httpStream)
  }

  val faceContentGetEndpoint =
    faceEndpoint()
      .name("Get face image")
      .summary("Get face image content")
      .get
      .in(path[String]("faceId"))
      .in("content")
      .out(header[String]("Content-Type"))
      .out(streamBinaryBody(ZioStreams)(CodecFormat.OctetStream()))
      .errorOut(oneOf(statusForApiInternalError, statusForApiResourceNotFound, statusForApiInvalidRequestError))
      .zServerLogic[ApiEnv](rawFaceId =>
        extractFaceId(rawFaceId)
          .flatMap(id => faceGetImageBytesLogic(id))
      )

  // -------------------------------------------------------------------------------------------------------------------

  val adminSynchronizeEndpoint =
    adminEndpoint
      .name("Synchronize start")
      .summary("Start synchronize background operations with all stores content")
      .put
      .in("synchronize")
      .in(query[Option[Int]]("addedThoseLastDays").description("for faster synchronize operations provide how much days back to look for new medias"))
      .errorOut(oneOf(statusForApiInternalError))
      .zServerLogic[ApiEnv] { addedThoseLastDays =>
        MediaService
          .synchronizeStart(addedThoseLastDays)
          .mapError(err => ApiInternalError("Couldn't synchronize"))
      }

  val adminSynchronizeStatusEndpoint =
    adminEndpoint
      .name("Get synchronize status")
      .summary("Get all the details about what's going on with the synchronize operations")
      .get
      .in("synchronize")
      .out(jsonBody[ApiSynchronizeStatus])
      .errorOut(oneOf(statusForApiInternalError))
      .zServerLogic[ApiEnv] { _ =>
        MediaService
          .synchronizeStatus()
          .map(_.transformInto[ApiSynchronizeStatus])
          .mapError(err => ApiInternalError("Couldn't get synchronize status"))
      }

  // -------------------------------------------------------------------------------------------------------------------

  def eventGetLogic(eventId: EventId): ZIO[ApiEnv, ApiIssue, ApiEvent] = {
    val logic = for {
      event   <- MediaService
                   .eventGet(eventId)
                   .logError("Couldn't get event")
                   .mapError(err => ApiInternalError("Couldn't get event"))
                   .someOrFail(ApiResourceNotFound("Couldn't find event"))
      taoEvent = event.transformInto[ApiEvent]
    } yield taoEvent

    logic
  }

  val eventGetEndpoint =
    eventEndpoint()
      .name("Get event")
      .summary("Get all event information for the given event identifier")
      .get
      .in(path[String]("eventId"))
      .out(jsonBody[ApiEvent])
      .errorOut(oneOf(statusForApiInternalError, statusForApiResourceNotFound, statusForApiInvalidRequestError))
      .zServerLogic[ApiEnv](rawEventId =>
        extractEventId(rawEventId)
          .flatMap(id => eventGetLogic(id))
      )

  def eventUpdateLogic(eventId: EventId, toUpdate: ApiEventUpdate): ZIO[ApiEnv, ApiIssue, Unit] = {
    val logic = for {
      event <- MediaService
                 .eventGet(eventId)
                 .logError("Couldn't get event")
                 .mapError(err => ApiInternalError("Couldn't get event"))
                 .someOrFail(ApiResourceNotFound("Couldn't find event"))
      _     <- MediaService
                 .eventUpdate(
                   eventId,
                   name = toUpdate.name,
                   description = toUpdate.description,
                   location = toUpdate.location.transformInto[Option[Location]],
                   timestamp = toUpdate.timestamp,
                   coverOriginalId = event.originalId,
                   publishedOn = toUpdate.publishedOn,
                   keywords = toUpdate.keywords
                 )
                 .logError("Couldn't update event")
                 .mapError(err => ApiInternalError("Couldn't update event"))
    } yield ()

    logic
  }

  val eventUpdateEndpoint =
    eventEndpoint()
      .name("Update event")
      .summary("Update event configuration for the given event identifier")
      .put
      .in(path[String]("eventId"))
      .in(jsonBody[ApiEventUpdate])
      .errorOut(oneOf(statusForApiInternalError, statusForApiResourceNotFound, statusForApiInvalidRequestError))
      .zServerLogic[ApiEnv]((rawEventId, toUpdate) =>
        extractEventId(rawEventId)
          .flatMap(eventId => eventUpdateLogic(eventId, toUpdate))
      )

  val eventUpdateCoverEndpoint =
    eventEndpoint()
      .name("Update event image cover")
      .summary("Update event image cover for the given event and original identifiers")
      .put
      .in(path[String]("eventId"))
      .in("cover")
      .in(path[String]("mediaAccessKey"))
      .errorOut(oneOf(statusForApiInternalError, statusForApiResourceNotFound, statusForApiInvalidRequestError))
      .zServerLogic[ApiEnv]((rawEventId, rawMediaAccessKey) =>
        for {
          eventId        <- extractEventId(rawEventId)
          mediaAccessKey <- extractMediaAccessKey(rawMediaAccessKey)
          event          <- MediaService
                              .eventGet(eventId)
                              .logError("Couldn't get event")
                              .mapError(err => ApiInternalError("Couldn't get event"))
                              .someOrFail(ApiResourceNotFound("Couldn't find event"))
          media          <- mediaGetLogic(mediaAccessKey)
          _              <- MediaService
                              .eventUpdate(
                                eventId = eventId,
                                name = event.name,
                                description = event.description,
                                location = event.location,
                                timestamp = event.timestamp,
                                coverOriginalId = Some(media.original.id),
                                publishedOn = event.publishedOn,
                                keywords = event.keywords
                              )
                              .logError("Couldn't update event")
                              .mapError(err => ApiInternalError("Couldn't update event"))

        } yield ()
      )

  val eventListLogic: ZStream[MediaService, Throwable, ApiEvent] = {
    MediaService
      .eventList()
      .map(event => event.transformInto[ApiEvent])
      .mapError(err => ApiInternalError("Couldn't list events"))
  }

  def eventDeleteLogic(eventId: EventId): ZIO[ApiEnv, ApiIssue, Unit] = {
    val logic = for {
      event <- MediaService
                 .eventGet(eventId)
                 .logError("Couldn't get event")
                 .orElseFail(ApiInternalError("Couldn't get event"))
                 .someOrFail(ApiResourceNotFound("Couldn't find event"))
      _     <- ZIO
                 .fail(ApiInvalidOrMissingInput("Event can't be deleted because it has an attachment"))
                 .when(event.attachment.nonEmpty)
      _     <- MediaService
                 .eventDelete(eventId)
                 .logError("Couldn't delete event")
                 .orElseFail(ApiInternalError("Couldn't delete event"))
    } yield ()
    logic
  }

  val eventDeleteEndpoint =
    eventEndpoint()
      .name("Delete event")
      .summary("Delete the event for the given event identifier")
      .delete
      .in(path[String]("eventId"))
      .errorOut(oneOf(statusForApiInternalError, statusForApiResourceNotFound, statusForApiInvalidRequestError))
      .zServerLogic[ApiEnv](rawEventId =>
        ZIO
          .attempt(EventId(java.util.UUID.fromString(rawEventId)))
          .orElseFail(ApiInvalidOrMissingInput("Invalid event identifier"))
          .flatMap(eventId => eventDeleteLogic(eventId))
      )

  val eventListEndpoint =
    eventEndpoint(true)
      .name("List events")
      .summary("Stream all defined events")
      .get
      .out(
        streamBody(ZioStreams)(ApiEvent.apiEventSchema, NdJson, Some(StandardCharsets.UTF_8))
          .description("NDJSON (one Event JSON object per line)")
          // .schema(summon[Schema[List[ApiEvent]]])
      ) // TODO how to provide information about the fact we want NDJSON output of ApiEvent ?
      .errorOut(oneOf(statusForApiInternalError))
      .zServerLogic[ApiEnv](_ =>
        for {
          ms        <- ZIO.service[MediaService]
          byteStream = eventListLogic
                         .map(_.toJson)
                         .intersperse("\n")
                         .via(ZPipeline.utf8Encode)
                         .provideEnvironment(ZEnvironment(ms))
        } yield byteStream
      )

  // -------------------------------------------------------------------------------------------------------------------

  def eventCreateLogic(toCreate: ApiEventCreate): ZIO[ApiEnv, ApiInternalError, ApiEvent] = {
    val logic = for {
      created <- MediaService
                   .eventCreate(
                     attachment = None, // user-defined event, no attachment
                     name = toCreate.name,
                     description = toCreate.description,
                     keywords = toCreate.keywords,
                     location = None,
                     timestamp = None,
                     originalId = None
                   )
                   .logError("Couldn't create event")
                   .orElseFail(ApiInternalError("Couldn't create event"))
      api      = created.transformInto[ApiEvent]
    } yield api
    logic
  }

  val eventCreateEndpoint =
    eventEndpoint()
      .name("Create event")
      .summary("Create a user-defined event")
      .post
      .in(jsonBody[ApiEventCreate])
      .out(jsonBody[ApiEvent])
      .errorOut(oneOf(statusForApiInternalError))
      .zServerLogic[ApiEnv](toCreate => eventCreateLogic(toCreate))

  // -------------------------------------------------------------------------------------------------------------------
  val serviceStatusLogic = ZIO.succeed(ApiStatus(alive = true))

  val serviceStatusEndpoint =
    systemEndpoint
      .name("Service status")
      .summary("Get the service status")
      .description("Returns the service status, can also be used as a health check end point for monitoring purposes")
      .get
      .in("status")
      .out(jsonBody[ApiStatus])
      .zServerLogic[ApiEnv](_ => serviceStatusLogic)

  // -------------------------------------------------------------------------------------------------------------------
  val serviceInfoLogic = for {
    originalsCount <- MediaService
                        .originalCount()
                        .logError
                        .mapError(err => ApiInternalError("Couldn't get originals count"))
  } yield ApiInfo(
    authors = List("@crodav"),
    version = "0.1",
    message = "Enjoy your photos/videos",
    originalsCount = originalsCount
  )

  val serviceInfoEndpoint =
    systemEndpoint
      .name("Service global information")
      .summary("Get information and some global statistics")
      .description("Returns service global information such as release information, authors and global statistics")
      .get
      .in("info")
      .out(jsonBody[ApiInfo])
      .errorOut(oneOf(statusForApiInternalError))
      .zServerLogic[ApiEnv](_ => serviceInfoLogic)

  // -------------------------------------------------------------------------------------------------------------------
  val apiRoutes = List(
    // -------------------------
    mediaListEndpoint,
    mediaSelectEndpoint,
    mediaGetEndpoint,
    mediaUpdateEndpoint,
    mediaUpdateStarredEndpoint,
    mediaContentGetOriginalEndpoint,
    mediaContentGetNormalizedEndpoint,
    mediaContentGetMiniatureEndpoint,
    // -------------------------
    mediaFacesGetEndpoint,
    // -------------------------
    faceListEndpoint,
    faceGetEndpoint,
    faceContentGetEndpoint,
    // -------------------------
    eventListEndpoint,
    eventCreateEndpoint,
    eventGetEndpoint,
    eventUpdateEndpoint,
    eventUpdateCoverEndpoint,
    eventDeleteEndpoint,
    // -------------------------
    ownerCreateEndpoint,
    ownerListEndpoint,
    ownerGetEndpoint,
    ownerUpdateEndpoint,
    ownerUpdateCoverEndpoint,
    // -------------------------
    storeCreateEndpoint,
    storeListEndpoint,
    storeGetEndpoint,
    storeUpdateEndpoint,
    // -------------------------
    stateGetEndpoint,
    // -------------------------
    adminSynchronizeEndpoint,
    adminSynchronizeStatusEndpoint,
    // -------------------------
    serviceStatusEndpoint,
    serviceInfoEndpoint
  )

  def apiDocRoutes =
    SwaggerInterpreter()
      .fromServerEndpoints(
        apiRoutes,
        Info(title = "SOTOHP API", version = "1.0", description = Some("Medias management software by @crodav"))
      )

  val staticHeaders = List(
    Header.cacheControl(
      CacheDirective.MaxAge(FiniteDuration(15, TimeUnit.MINUTES))
    )
  )

  def htmlUserInterfaceRedirect(uiBaseDir: String): ZServerEndpoint[ApiEnv, Any] = {
    endpoint.get
      .in("")
      .out(statusCode(StatusCode.TemporaryRedirect))
      .out(header(HeaderNames.Location, s"/ui/index.html"))
      .serverLogicSuccess(_ => ZIO.succeed(()))
  }

  def htmlStaticAssets(uiBaseDir: String): List[ZServerEndpoint[ApiEnv, Any]] = {
    List(
      staticFilesGetServerEndpoint("ui" / "assets")(s"$uiBaseDir/assets").widen[ApiEnv],
      staticFilesGetServerEndpoint("ui" / "favicon.svg")(s"$uiBaseDir/favicon.svg").widen[ApiEnv],
      staticFilesGetServerEndpoint("ui" / "index.html")(s"$uiBaseDir/index.html").widen[ApiEnv]
    )
  }

  def htmlRouteFallback(uiBaseDir: String): ZServerEndpoint[ApiEnv, Any] = {
    endpoint.get
      .in("ui")
      .in(paths)
      .out(htmlBodyUtf8)
      .serverLogicSuccess { _ =>
        for { // TODO add caching
          filePath <- ZIO.attempt(Path.of(uiBaseDir, "index.html"))
          bytes    <- ZIO.attemptBlocking(Files.readAllBytes(filePath))
          content  <- ZIO.attempt(new String(bytes, "UTF-8"))
        } yield content
      }
      .widen[ApiEnv]
  }

  def buildFrontRoutes: IO[Throwable, List[ZServerEndpoint[ApiEnv, Any]]] = for {
    dir       <- ApiConfig.config.map(_.clientResourcesPath)
    uiBaseDir <- ZIO
                   .attempt(
                     Path
                       .of(dir)
                       .toAbsolutePath
                       .normalize()
                       .toString
                   )
                   .logError("Issue with the user interface resources path")
    _         <- ZIO.logInfo(s"User interface resources path: $uiBaseDir")
  } yield {
    htmlStaticAssets(uiBaseDir)
      :+ htmlRouteFallback(uiBaseDir)
      :+ htmlUserInterfaceRedirect(uiBaseDir)
  }

  def server = for {
    _           <- ZIO.logInfo("Starting service")
    frontRoutes <- buildFrontRoutes
    _           <- ZIO.logInfo("Front routes are ready")
    allRoutes    = apiRoutes ++ apiDocRoutes ++ frontRoutes
    httpApp      = ZioHttpInterpreter().toHttp(allRoutes)
    _           <- ZIO.logInfo("All routes are ready")
    zservice    <- Server.serve(httpApp)
  } yield zservice

  val serverConfigLayer = ZLayer.fromZIO {
    for {
      config <- ApiConfig.config
      port    = config.listeningPort
      nif     = "0.0.0.0"
    } yield {
      Server.Config.default
        .binding(nif, port)
        .copy(
          responseCompression = None,
          soBacklog = 100,
          avoidContextSwitching = false,
          tcpNoDelay = true,
          keepAlive = true
        )
    }
  }

  override def run = {
    for {
      config <- ApiConfig.config
      _      <- server
                  .provide(
                    LMDB.live,
                    MediaService.live,
                    SearchService.live,
                    serverConfigLayer,
                    Server.live,
                    Scope.default
                  )
    } yield ()
  }
}
