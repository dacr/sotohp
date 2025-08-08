package fr.janalyse.sotohp.api

import com.typesafe.config.ConfigFactory
import fr.janalyse.sotohp.search.SearchService
import zio.*
import sttp.apispec.openapi.Info
import sttp.model.{Header, StatusCode}
import sttp.tapir.generic.auto.*
import sttp.tapir.json.zio.*
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import sttp.tapir.swagger.bundle.SwaggerInterpreter
import sttp.tapir.ztapir.*
import zio.logging.backend.SLF4J
import zio.logging.LogFormat
import zio.{LogLevel, System, ZIOAppDefault}
import fr.janalyse.sotohp.service.MediaService
import fr.janalyse.sotohp.api.protocol.*
import sttp.model.headers.CacheDirective
import sttp.tapir.files.staticFilesGetServerEndpoint
import zio.Runtime.removeDefaultLoggers
import zio.config.typesafe.TypesafeConfigProvider
import zio.http.Server
import zio.lmdb.LMDB

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration

object ApiApp extends ZIOAppDefault {

  type ApiEnv = MediaService

  // -------------------------------------------------------------------------------------------------------------------

  val configProvider      = TypesafeConfigProvider.fromTypesafeConfig(com.typesafe.config.ConfigFactory.load())
  val configProviderLayer = Runtime.setConfigProvider(configProvider)

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] = {
    val loggingLayer = removeDefaultLoggers >>> SLF4J.slf4j(format = LogFormat.colored)
    loggingLayer ++ configProviderLayer
  }

  // -------------------------------------------------------------------------------------------------------------------
  val userAgent = header[Option[String]]("User-Agent").schema(_.hidden(true))

  val statusForApiInternalError    = oneOfVariant(StatusCode.InternalServerError, jsonBody[ApiInternalError].description("Something went wrong with the backend"))
  val statusForApiResourceNotFound = oneOfVariant(StatusCode.NotFound, jsonBody[ApiResourceNotFound].description("Couldn't find the request resource"))

  // -------------------------------------------------------------------------------------------------------------------
  val systemEndpoint = endpoint.in("api").in("system").tag("System")
  val mediaEndpoint  = endpoint.in("api").in("media").tag("Media")

  // -------------------------------------------------------------------------------------------------------------------
  val mediaRandomLogic = for {
    count           <- MediaService.originalCount()
    index           <- Random.nextLongBounded(count)
    media           <- MediaService.mediaGetAt(index).some // TODO Remember mediaGetAt is not performance optimal due to the nature of lmdb
    imageBytesStream = MediaService.mediaNormalizedRead(media.accessKey)
  } yield imageBytesStream

  // -------------------------------------------------------------------------------------------------------------------

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
  val apiRoutes: List[ZServerEndpoint[ApiEnv, Any]] = List(
    serviceStatusEndpoint,
    serviceInfoEndpoint
  )

  def apiDocRoutes =
    SwaggerInterpreter()
      .fromServerEndpoints(
        apiRoutes,
        Info(title = "SOTOHP API", version = "1.0", description = Some("Photos management software by @crodav"))
      )

  val staticHeaders = List(
    Header.cacheControl(
      CacheDirective.MaxAge(FiniteDuration(15, TimeUnit.MINUTES))
    )
  )

  def buildFrontRoutes: IO[Exception, List[ZServerEndpoint[ApiEnv, Any]]] = for {
    config                      <- ApiConfig.config
    clientSideResourcesEndPoints = staticFilesGetServerEndpoint(emptyInput)(config.clientResourcesPath, extraHeaders = staticHeaders).widen[ApiEnv]
    clientSideRoutes             = List(clientSideResourcesEndPoints)
  } yield clientSideRoutes

  def server = for {
    frontRoutes <- buildFrontRoutes
    allRoutes    = apiRoutes ++ apiDocRoutes ++ frontRoutes
    httpApp      = ZioHttpInterpreter().toHttp(allRoutes)
    _           <- ZIO.logInfo("Starting service")
    zservice    <- Server.serve(httpApp)
  } yield zservice

  override def run = {
    for {
      config <- ApiConfig.config
      _      <- server.provide(
                  LMDB.live,
                  MediaService.live,
                  SearchService.live,
                  Server.defaultWithPort(config.listeningPort),
                  Scope.default
                )
    } yield ()
  }
}
