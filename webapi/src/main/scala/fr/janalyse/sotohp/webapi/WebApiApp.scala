package fr.janalyse.sotohp.webapi

import zio.*
import sttp.apispec.openapi.Info
import sttp.model.StatusCode
import sttp.tapir.generic.auto.*
import sttp.tapir.json.zio.*
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import sttp.tapir.swagger.bundle.SwaggerInterpreter
import sttp.tapir.ztapir.{oneOfVariant, *}
import zio.logging.backend.SLF4J
import zio.logging.{LogFormat, removeDefaultLoggers}
import zio.{LogLevel, System, ZIOAppDefault}
import fr.janalyse.sotohp.core.store.PersistenceService
import fr.janalyse.sotohp.webapi.protocol.*

object WebApiApp extends ZIOAppDefault {

  type WebApiEnv = PersistenceService

  // -------------------------------------------------------------------------------------------------------------------

  lazy val loggingLayer = removeDefaultLoggers >>> SLF4J.slf4j(
    LogLevel.Debug,
    format = LogFormat.colored
  )

  override val bootstrap = loggingLayer

  // -------------------------------------------------------------------------------------------------------------------
  val systemEndpoint = endpoint.in("api").in("system").tag("System")

  // -------------------------------------------------------------------------------------------------------------------
  val userAgent = header[Option[String]]("User-Agent").schema(_.hidden(true))

  val statusForServiceInternalError = oneOfVariant(StatusCode.InternalServerError, jsonBody[ServiceInternalError].description("Something went wrong with the backend"))

  // -------------------------------------------------------------------------------------------------------------------
  val serviceStatusLogic = ZIO.succeed(ServiceStatus(alive = true))
  val serviceInfoLogic   = ZIO.succeed(
    ServiceInfo(
      authors = List("@BriossantC", "@crodav"),
      version = "0.1",
      message = "Enjoy your photos/videos",
      photosCount = 0,
      videosCount = 0
    )
  )
  // -------------------------------------------------------------------------------------------------------------------

  val serviceStatusEndpoint =
    systemEndpoint
      .name("Service status")
      .summary("Get the service status")
      .description("Returns the service status, can also be used as a health check end point for monitoring purposes")
      .get
      .in("status")
      .out(jsonBody[ServiceStatus])
      .zServerLogic[WebApiEnv](_ => serviceStatusLogic)

  // -------------------------------------------------------------------------------------------------------------------

  val serviceInfoEndpoint =
    systemEndpoint
      .name("Service global information")
      .summary("Get information and some global statistics")
      .description("Returns service global information such as release information, authors and global statistics")
      .get
      .in("info")
      .out(jsonBody[ServiceInfo])
      .errorOut(oneOf(statusForServiceInternalError))
      .zServerLogic[WebApiEnv](_ => serviceInfoLogic)

  // -------------------------------------------------------------------------------------------------------------------
  val apiRoutes = List(
    serviceStatusEndpoint,
    serviceInfoEndpoint
  )

  def apiDocRoutes =
    SwaggerInterpreter()
      .fromServerEndpoints(
        apiRoutes,
        Info(title = "SOTOHP API", version = "1.0", description = Some("Photos management software by @crodav"))
      )

  def server = for {
    _               <- ZIO.log("SOTOHP starting...")
    clientResources <- System.env("SOTOHP_CLIENT_RESOURCES_PATH")
    clientSideRoutes = clientResources.map(cr => filesGetServerEndpoint(emptyInput)(cr).widen[WebApiEnv])
    httpApp          = ZioHttpInterpreter().toHttp(apiRoutes ++ apiDocRoutes ++ clientSideRoutes).provideSomeLayer(loggingLayer)
    zservice        <- zhttp.service.Server.start(8090, httpApp)
  } yield zservice

  override def run = server.provide(PersistenceService.live)

}
