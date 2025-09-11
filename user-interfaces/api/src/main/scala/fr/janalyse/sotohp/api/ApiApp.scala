package fr.janalyse.sotohp.api

import com.typesafe.config.ConfigFactory
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
import zio.logging.backend.SLF4J
import zio.logging.LogFormat
import fr.janalyse.sotohp.service.{MediaService, ServiceStreamIssue}
import fr.janalyse.sotohp.api.protocol.{*, given}
import fr.janalyse.sotohp.model.*
import sttp.capabilities.zio.ZioStreams
import sttp.model.headers.CacheDirective
import sttp.tapir.{CodecFormat, EndpointInput, Schema}
import sttp.tapir.files.staticFilesGetServerEndpoint
import zio.config.typesafe.TypesafeConfigProvider
import zio.http.Server
import zio.lmdb.LMDB

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import io.scalaland.chimney.dsl.*
import zio.stream.{ZPipeline, ZStream}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

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
    val fmt     = LogFormat.annotations |-| LogFormat.line
    val logging = Runtime.removeDefaultLoggers >>> SLF4J.slf4j(format = fmt)
    logging ++ configProviderLayer
  }

  // -------------------------------------------------------------------------------------------------------------------
  val userAgent = header[Option[String]]("User-Agent").schema(_.hidden(true))

  val statusForApiInvalidIdentifier = oneOfVariant(StatusCode.BadRequest, jsonBody[ApiInvalidIdentifier].description("Invalid identifier provided"))
  val statusForApiInternalError     = oneOfVariant(StatusCode.InternalServerError, jsonBody[ApiInternalError].description("Something went wrong with the backend"))
  val statusForApiResourceNotFound  = oneOfVariant(StatusCode.NotFound, jsonBody[ApiResourceNotFound].description("Couldn't find the request resource"))

  // -------------------------------------------------------------------------------------------------------------------
  val systemEndpoint = endpoint.in("api").in("system").tag("System")
  val adminEndpoint  = endpoint.in("api").in("admin").tag("Admin")
  val mediaEndpoint  = endpoint.in("api").in("media").tag("Media")
  val storeEndpoint  = endpoint.in("api").in("store").tag("Store")
  val ownerEndpoint  = endpoint.in("api").in("owner").tag("Owner")
  val eventEndpoint  = endpoint.in("api").in("event").tag("Event")

  // -------------------------------------------------------------------------------------------------------------------

  def ownerGetLogic(ownerId: OwnerId): ZIO[ApiEnv, ApiIssue, ApiOwner] = {
    val logic = for {
      owner   <- MediaService
                   .ownerGet(ownerId)
                   .logError("Couldn't get owner")
                   .mapError(err => ApiInternalError("Couldn't get owner"))
                   .someOrFail(ApiResourceNotFound("Couldn't find owner"))
      taoOwner = owner.transformInto[ApiOwner]
    } yield taoOwner

    logic
  }

  val ownerGetEndpoint =
    ownerEndpoint
      .name("Get owner")
      .summary("Get all owner information for the given owner identifier")
      .get
      .in(path[String]("ownerId"))
      .out(jsonBody[ApiOwner])
      .errorOut(oneOf(statusForApiInternalError, statusForApiResourceNotFound, statusForApiInvalidIdentifier))
      .zServerLogic[ApiEnv](rawId =>
        ZIO
          .attempt(OwnerId.fromString(rawId))
          .mapError(err => ApiInvalidIdentifier("Invalid owner identifier"))
          .flatMap(key => ownerGetLogic(key))
      )

  def ownerUpdateLogic(ownerId: OwnerId, toUpdate: ApiOwnerUpdate): ZIO[ApiEnv, ApiIssue, Unit] = {
    val logic = for {
      owner <- MediaService
                 .ownerGet(ownerId)
                 .logError("Couldn't get owner")
                 .mapError(err => ApiInternalError("Couldn't get owner"))
                 .someOrFail(ApiResourceNotFound("Couldn't find owner"))
      _     <- MediaService
                 .ownerUpdate(ownerId, firstName = toUpdate.firstName, lastName = toUpdate.lastName, birthDate = toUpdate.birthDate)
                 .logError("Couldn't update owner")
                 .mapError(err => ApiInternalError("Couldn't update owner"))
    } yield ()

    logic
  }

  val ownerUpdateEndpoint =
    ownerEndpoint
      .name("Update owner")
      .summary("Update owner configuration for the given owner identifier")
      .put
      .in(path[String]("ownerId"))
      .in(jsonBody[ApiOwnerUpdate]) // TODO security and regex fields
      .errorOut(oneOf(statusForApiInternalError, statusForApiResourceNotFound, statusForApiInvalidIdentifier))
      .zServerLogic[ApiEnv]((rawOwnerId, toUpdate) =>
        ZIO
          .attempt(OwnerId.fromString(rawOwnerId))
          .mapError(err => ApiInvalidIdentifier("Invalid owner identifier"))
          .flatMap(ownerId => ownerUpdateLogic(ownerId, toUpdate))
      )

  val ownerListLogic: ZStream[MediaService, Throwable, ApiOwner] = {
    MediaService
      .ownerList()
      .map(owner => owner.transformInto[ApiOwner])
      .mapError(err => ApiInternalError("Couldn't list owners"))
  }

  val ownerListEndpoint =
    ownerEndpoint
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
    storeEndpoint
      .name("Get store")
      .summary("Get all store information for the given store identifier")
      .get
      .in(path[String]("storeId"))
      .out(jsonBody[ApiStore])
      .errorOut(oneOf(statusForApiInternalError, statusForApiResourceNotFound, statusForApiInvalidIdentifier))
      .zServerLogic[ApiEnv](rawId =>
        ZIO
          .attempt(StoreId.fromString(rawId))
          .mapError(err => ApiInvalidIdentifier("Invalid store identifier"))
          .flatMap(id => storeGetLogic(id))
      )

  def storeUpdateLogic(storeId: StoreId, toUpdate: ApiStoreUpdate): ZIO[ApiEnv, ApiIssue, Unit] = {
    val logic = for {
      store <- MediaService
                 .storeGet(storeId)
                 .logError("Couldn't get store")
                 .mapError(err => ApiInternalError("Couldn't get store"))
                 .someOrFail(ApiResourceNotFound("Couldn't find store"))
      _     <- MediaService
                 .storeUpdate(storeId, includeMask = toUpdate.includeMask, ignoreMask = toUpdate.ignoreMask)
                 .logError("Couldn't update store")
                 .mapError(err => ApiInternalError("Couldn't update store"))
    } yield ()

    logic
  }

  val storeUpdateEndpoint =
    storeEndpoint
      .name("Update store")
      .summary("Update store configuration for the given store identifier")
      .put
      .in(path[String]("storeId"))
      .in(jsonBody[ApiStoreUpdate]) // TODO security and regex fields
      .errorOut(oneOf(statusForApiInternalError, statusForApiResourceNotFound, statusForApiInvalidIdentifier))
      .zServerLogic[ApiEnv]((rawStoreId, toUpdate) =>
        ZIO
          .attempt(StoreId.fromString(rawStoreId))
          .mapError(err => ApiInvalidIdentifier("Invalid store identifier"))
          .flatMap(storeId => storeUpdateLogic(storeId, toUpdate))
      )

  val storeListLogic: ZStream[MediaService, Throwable, ApiStore] = {
    MediaService
      .storeList()
      .map(store => store.transformInto[ApiStore])
      .mapError(err => ApiInternalError("Couldn't list stores"))
  }

  val storeListEndpoint =
    storeEndpoint
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
    mediaEndpoint
      .name("Get media")
      .summary("Get all media information for the given media access key")
      .get
      .in(path[String]("mediaAccessKey"))
      .out(jsonBody[ApiMedia])
      .errorOut(oneOf(statusForApiInternalError, statusForApiResourceNotFound, statusForApiInvalidIdentifier))
      .zServerLogic[ApiEnv](rawKey =>
        ZIO
          .attempt(MediaAccessKey(rawKey))
          .mapError(err => ApiInvalidIdentifier("Invalid media access key"))
          .flatMap(key => mediaGetLogic(key))
      )

  // -------------------------------------------------------------------------------------------------------------------

  def mediaListLogic(filterHasLocation:Option[Boolean]): ZStream[MediaService, Throwable, ApiMedia] = {
    MediaService
      .mediaList()
      .filter(media => filterHasLocation.isEmpty || media.location.isDefined == filterHasLocation.get)
      .map(media => media.transformInto[ApiMedia](using ApiMedia.transformer))
      .mapError(err => ApiInternalError("Couldn't list medias"))
  }

  val mediaListEndpoint =
    mediaEndpoint
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
      .zServerLogic[ApiEnv]( (filterHasLocation) =>
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
  val mediaRandomLogic = {
    val logic = for {
      count           <- MediaService
                           .originalCount()
                           .mapError(err => ApiInternalError("Couldn't get medias count"))
      index           <- Random.nextLongBounded(count)
      media           <- MediaService
                           .mediaGetAt(index)
                           .some // TODO Remember mediaGetAt is not performance optimal due to the nature of lmdb
                           .mapError(err => ApiInternalError("Couldn't get a random media"))
      imageBytesStream = MediaService
                           .mediaNormalizedRead(media.accessKey)
                           .mapError(err => ApiInternalError("Couldn't read media"))
    } yield (media, imageBytesStream)

    logic
  }

  val mediaRandomEndpoint =
    mediaEndpoint
      .name("Random Media")
      .summary("Get a randomly chosen media")
      .get
      .in("random")
      .out(header[String]("Content-Type"))
      .out(header[String]("Media-Access-Key"))
      .out(streamBinaryBody(ZioStreams)(CodecFormat.OctetStream()))
      .errorOut(oneOf(statusForApiInternalError))
      .zServerLogic[ApiEnv](_ =>
        for {
          (media, byteStream) <- mediaRandomLogic
          ms                  <- ZIO.service[MediaService]
          httpStream           = byteStream.provideEnvironment(ZEnvironment(ms))
        } yield (MediaType.ImageJpeg.toString, media.accessKey.asString, httpStream)
      )

  // -------------------------------------------------------------------------------------------------------------------

  val adminSynchronizeEndpoint =
    adminEndpoint
      .name("Synchronize")
      .summary("Synchronize with all stores content")
      .get
      .in("synchronize")
      .errorOut(oneOf(statusForApiInternalError))
      .zServerLogic[ApiEnv] { _ =>
        MediaService
          .synchronize()
          .mapError(err => ApiInternalError("Couldn't synchronize"))
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
    eventEndpoint
      .name("Get event")
      .summary("Get all event information for the given event identifier")
      .get
      .in(path[String]("eventId"))
      .out(jsonBody[ApiEvent])
      .errorOut(oneOf(statusForApiInternalError, statusForApiResourceNotFound, statusForApiInvalidIdentifier))
      .zServerLogic[ApiEnv](rawId =>
        ZIO
          .attempt(EventId(java.util.UUID.fromString(rawId)))
          .mapError(err => ApiInvalidIdentifier("Invalid event identifier"))
          .flatMap(id => eventGetLogic(id))
      )

  def eventUpdateLogic(eventId: EventId, toUpdate: ApiEventUpdate): ZIO[ApiEnv, ApiIssue, Unit] = {
    val logic = for {
      _ <- MediaService
             .eventGet(eventId)
             .logError("Couldn't get event")
             .mapError(err => ApiInternalError("Couldn't get event"))
             .someOrFail(ApiResourceNotFound("Couldn't find event"))
      _ <- MediaService
             .eventUpdate(eventId, name = toUpdate.name, description = toUpdate.description, keywords = toUpdate.keywords)
             .logError("Couldn't update event")
             .mapError(err => ApiInternalError("Couldn't update event"))
    } yield ()

    logic
  }

  val eventUpdateEndpoint =
    eventEndpoint
      .name("Update event")
      .summary("Update event configuration for the given event identifier")
      .put
      .in(path[String]("eventId"))
      .in(jsonBody[ApiEventUpdate])
      .errorOut(oneOf(statusForApiInternalError, statusForApiResourceNotFound, statusForApiInvalidIdentifier))
      .zServerLogic[ApiEnv]((rawEventId, toUpdate) =>
        ZIO
          .attempt(EventId(java.util.UUID.fromString(rawEventId)))
          .mapError(err => ApiInvalidIdentifier("Invalid event identifier"))
          .flatMap(eventId => eventUpdateLogic(eventId, toUpdate))
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
                 .fail(ApiInvalidIdentifier("Event can't be deleted because it has an attachment"))
                 .when(event.attachment.nonEmpty)
      _     <- MediaService
                 .eventDelete(eventId)
                 .logError("Couldn't delete event")
                 .orElseFail(ApiInternalError("Couldn't delete event"))
    } yield ()
    logic
  }

  val eventDeleteEndpoint =
    eventEndpoint
      .name("Delete event")
      .summary("Delete the event for the given event identifier")
      .delete
      .in(path[String]("eventId"))
      .errorOut(oneOf(statusForApiInternalError, statusForApiResourceNotFound, statusForApiInvalidIdentifier))
      .zServerLogic[ApiEnv](rawEventId =>
        ZIO
          .attempt(EventId(java.util.UUID.fromString(rawEventId)))
          .orElseFail(ApiInvalidIdentifier("Invalid event identifier"))
          .flatMap(eventId => eventDeleteLogic(eventId))
      )

  val eventListEndpoint =
    eventEndpoint
      .name("List events")
      .summary("Stream all defined events")
      .get
      .out(
        streamBody(ZioStreams)(ApiEvent.apiEventSchema, NdJson, Some(StandardCharsets.UTF_8))
          .description("NDJSON (one Event JSON object per line)")
      )
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
                     keywords = toCreate.keywords
                   )
                   .logError("Couldn't create event")
                   .orElseFail(ApiInternalError("Couldn't create event"))
      api      = created.transformInto[ApiEvent]
    } yield api
    logic
  }

  val eventCreateEndpoint =
    eventEndpoint
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
    mediaRandomEndpoint,
    mediaListEndpoint,
    mediaGetEndpoint,
    // -------------------------
    eventCreateEndpoint,
    eventListEndpoint,
    eventGetEndpoint,
    eventUpdateEndpoint,
    eventDeleteEndpoint,
    // -------------------------
    ownerListEndpoint,
    ownerGetEndpoint,
    ownerUpdateEndpoint,
    // -------------------------
    storeListEndpoint,
    storeGetEndpoint,
    storeUpdateEndpoint,
    // -------------------------
    adminSynchronizeEndpoint,
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

  def buildFrontRoutes: IO[Exception, List[ZServerEndpoint[ApiEnv, Any]]] = for {
    uiBaseDir <- ApiConfig.config.map(_.clientResourcesPath)
  } yield {
    htmlStaticAssets(uiBaseDir)
      :+ htmlRouteFallback(uiBaseDir)
      :+ htmlUserInterfaceRedirect(uiBaseDir)
  }

  def server = for {
    frontRoutes <- buildFrontRoutes
    allRoutes    = apiRoutes ++ apiDocRoutes ++ frontRoutes
    httpApp      = ZioHttpInterpreter().toHttp(allRoutes)
    _           <- ZIO.logInfo("Starting service")
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
    }
  }

  override def run = {
    for {
      config <- ApiConfig.config
      _      <- server.provide(
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
