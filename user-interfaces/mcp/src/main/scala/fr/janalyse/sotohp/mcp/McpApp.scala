package fr.janalyse.sotohp.mcp

import zio.*
import zio.json.*
import zio.json.ast.*
import zio.stream.*
import sttp.client4.*
import sttp.client4.httpclient.zio.HttpClientZioBackend
import sttp.capabilities.zio.ZioStreams
import sttp.model.Uri
import fr.janalyse.sotohp.mcp.ApiCodecs.*
import fr.janalyse.sotohp.model.*
import java.io.IOException

// --- MCP Models (Keep these as they are specific to MCP protocol) ---

case class McpRequest(
  jsonrpc: String,
  id: Option[Json] = None,
  method: String,
  params: Option[Json] = None
)
object McpRequest {
  implicit val decoder: JsonDecoder[McpRequest] = DeriveJsonDecoder.gen[McpRequest]
}

case class McpResponse(
  jsonrpc: String = "2.0",
  id: Option[Json] = None,
  result: Option[Json] = None,
  error: Option[McpError] = None
)
object McpResponse {
  implicit val encoder: JsonEncoder[McpResponse] = DeriveJsonEncoder.gen[McpResponse]
}

case class McpError(code: Int, message: String, data: Option[Json] = None)
object McpError {
  implicit val encoder: JsonEncoder[McpError] = DeriveJsonEncoder.gen[McpError]
}

// ... (Other MCP models from previous step, assumed to be here or I re-declare them for completeness)
// For brevity, reusing the structures defined previously.

case class McpServerInfo(name: String, version: String)
object McpServerInfo { implicit val encoder: JsonEncoder[McpServerInfo] = DeriveJsonEncoder.gen }

case class McpCapabilities(tools: Json.Obj = Json.Obj())
object McpCapabilities { implicit val encoder: JsonEncoder[McpCapabilities] = DeriveJsonEncoder.gen }

case class McpInitializeResult(protocolVersion: String, capabilities: McpCapabilities, serverInfo: McpServerInfo)
object McpInitializeResult { implicit val encoder: JsonEncoder[McpInitializeResult] = DeriveJsonEncoder.gen }

case class McpInputSchema(`type`: String, properties: Map[String, Json], required: List[String] = Nil)
object McpInputSchema { implicit val encoder: JsonEncoder[McpInputSchema] = DeriveJsonEncoder.gen }

case class McpTool(name: String, description: String, inputSchema: McpInputSchema)
object McpTool { implicit val encoder: JsonEncoder[McpTool] = DeriveJsonEncoder.gen }

case class McpListToolsResult(tools: List[McpTool])
object McpListToolsResult { implicit val encoder: JsonEncoder[McpListToolsResult] = DeriveJsonEncoder.gen }

case class McpContent(`type`: String, text: String)
object McpContent { implicit val encoder: JsonEncoder[McpContent] = DeriveJsonEncoder.gen }

case class McpCallToolResult(content: List[McpContent], isError: Boolean = false)
object McpCallToolResult { implicit val encoder: JsonEncoder[McpCallToolResult] = DeriveJsonEncoder.gen }

// --- Config ---

case class McpConfig(apiUrl: String, apiToken: Option[String])

object McpConfig {
  val live: ZLayer[Any, SecurityException, McpConfig] = ZLayer.fromZIO {
    for {
      url   <- System.envOrElse("SOTOHP_API_URL", "http://127.0.0.1:8080")
      token <- System.env("SOTOHP_API_TOKEN")
    } yield McpConfig(url, token)
  }
}

// --- App ---

object McpApp extends ZIOAppDefault {

  val stderrLogger: ZLogger[String, Unit] = (trace, fiberId, logLevel, message, context, spans, location, annotations) => {
    val msg = message()
    java.lang.System.err.println(s"[${logLevel.label}] $msg")
  }

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] = {
    Runtime.removeDefaultLoggers >>> Runtime.addLogger(stderrLogger) ++
    Runtime.enableAutoBlockingExecutor
  }

  // --- API Client Helpers ---

  def request(path: String)(using config: McpConfig): Request[Either[String, String]] = {
    val base = basicRequest.get(Uri.parse(config.apiUrl + path).getOrElse(throw new RuntimeException(s"Invalid URL: ${config.apiUrl}")))
    config.apiToken match {
      case Some(t) => base.header("Authorization", s"Bearer $t")
      case None    => base
    }
  }

  def streamRequest(path: String)(using config: McpConfig) = {
    val base = basicRequest
      .get(Uri.parse(config.apiUrl + path).getOrElse(throw new RuntimeException(s"Invalid URL: ${config.apiUrl}")))
      .response(asStreamUnsafe(ZioStreams))
    
    config.apiToken match {
      case Some(t) => base.header("Authorization", s"Bearer $t")
      case None    => base
    }
  }

  // --- Logic ---

  val Tools = List(
    McpTool("search_media", "Search photos by keywords (client-side filtering)", McpInputSchema("object", Map("keywords" -> Json.Obj("type" -> Json.Str("array"), "items" -> Json.Obj("type" -> Json.Str("string")))), List("keywords"))),
    McpTool("list_recent_media", "List the most recent media files", McpInputSchema("object", Map("limit" -> Json.Obj("type" -> Json.Str("integer"), "description" -> Json.Str("Number of media to return (default 10)"))))),
    McpTool("get_media_info", "Get detailed information for a specific media", McpInputSchema("object", Map("mediaAccessKey" -> Json.Obj("type" -> Json.Str("string"))), List("mediaAccessKey"))),
    McpTool("list_events", "List all events", McpInputSchema("object", Map("limit" -> Json.Obj("type" -> Json.Str("integer"))))),
    McpTool("get_event_info", "Get detailed information for a specific event", McpInputSchema("object", Map("eventId" -> Json.Obj("type" -> Json.Str("string"))), List("eventId"))),
    McpTool("list_people", "List all people", McpInputSchema("object", Map("limit" -> Json.Obj("type" -> Json.Str("integer"))))),
    McpTool("get_person_info", "Get detailed information for a specific person", McpInputSchema("object", Map("personId" -> Json.Obj("type" -> Json.Str("string"))), List("personId")))
  )

  def handleToolCall(name: String, arguments: Map[String, Json], backend: WebSocketStreamBackend[Task, ZioStreams])(using config: McpConfig): ZIO[Any, Throwable, McpCallToolResult] = {
    name match {
      case "search_media" =>
        val keywords = arguments.get("keywords").flatMap(_.as[List[String]].toOption).getOrElse(List.empty).map(Keyword.apply).toSet
        // Client-side filtering: Stream all medias, parse, filter.
        // Warning: This is heavy.
        val req = streamRequest("/api/medias")
        req.send(backend).flatMap { response =>
          response.body match {
            case Right(stream) =>
              stream
                .via(ZPipeline.utf8Decode >>> ZPipeline.splitLines)
                .map(_.fromJson[ApiMedia])
                .collectRight
                .filter(m => keywords.subsetOf(m.keywords)) // Check if requested keywords are subset of media keywords
                .take(10)
                .runCollect
                .map { medias =>
                  val text = medias.map(m => s"${m.accessKey.asString} - ${m.timestamp}").mkString("\n")
                  McpCallToolResult(List(McpContent("text", if (text.isEmpty) "No matches found." else text)))
                }
            case Left(err) => ZIO.fail(new RuntimeException(s"API Error: $err"))
          }
        }

      case "list_recent_media" =>
        val limit = arguments.get("limit").flatMap(_.as[Int].toOption).getOrElse(10)
        // Need to read whole stream to get the end (assuming chronological order)
        // Or just read first N? Original implementation was `takeRight`.
        // If API streams oldest first, we must read everything.
        // For efficiency, maybe just read last lines?
        // Let's just read and takeRight for now.
        val req = streamRequest("/api/medias")
        req.send(backend).flatMap { response =>
          response.body match {
            case Right(stream) =>
              stream
                .via(ZPipeline.utf8Decode >>> ZPipeline.splitLines)
                .map(_.fromJson[ApiMedia])
                .collectRight
                .runCollect
                .map(chunk => chunk.takeRight(limit))
                .map { medias =>
                  val text = medias.map(m => s"${m.accessKey.asString} - ${m.timestamp}").mkString("\n")
                  McpCallToolResult(List(McpContent("text", if (text.isEmpty) "No media found." else text)))
                }
            case Left(err) => ZIO.fail(new RuntimeException(s"API Error: $err"))
          }
        }

      case "get_media_info" =>
        val key = arguments.get("mediaAccessKey").flatMap(_.as[String].toOption).getOrElse("")
        val req = request(s"/api/media/$key")
        req.send(backend).flatMap { response =>
          response.body match {
            case Right(body) => ZIO.succeed(McpCallToolResult(List(McpContent("text", body))))
            case Left(err) => ZIO.succeed(McpCallToolResult(List(McpContent("text", s"Error: $err")), isError = true))
          }
        }

      case "list_events" =>
        val limit = arguments.get("limit").flatMap(_.as[Int].toOption).getOrElse(50)
        val req = streamRequest("/api/events")
        req.send(backend).flatMap { response =>
          response.body match {
            case Right(stream) =>
              stream
                .via(ZPipeline.utf8Decode >>> ZPipeline.splitLines)
                .take(limit) // Events might be unordered or sorted?
                .map(_.fromJson[ApiEvent])
                .collectRight
                .runCollect
                .map { events =>
                  val text = events.map(e => s"${e.id.toString} - ${e.name.text}").mkString("\n")
                  McpCallToolResult(List(McpContent("text", if (text.isEmpty) "No events found." else text)))
                }
            case Left(err) => ZIO.fail(new RuntimeException(s"API Error: $err"))
          }
        }

      case "get_event_info" =>
        val id = arguments.get("eventId").flatMap(_.as[String].toOption).getOrElse("")
        val req = request(s"/api/event/$id")
        req.send(backend).flatMap { response =>
          response.body match {
            case Right(body) => ZIO.succeed(McpCallToolResult(List(McpContent("text", body))))
            case Left(err) => ZIO.succeed(McpCallToolResult(List(McpContent("text", s"Error: $err")), isError = true))
          }
        }

      case "list_people" =>
        val limit = arguments.get("limit").flatMap(_.as[Int].toOption).getOrElse(50)
        val req = streamRequest("/api/persons")
        req.send(backend).flatMap { response =>
          response.body match {
            case Right(stream) =>
              stream
                .via(ZPipeline.utf8Decode >>> ZPipeline.splitLines)
                .take(limit)
                .map(_.fromJson[ApiPerson])
                .collectRight
                .runCollect
                .map { people =>
                  val text = people.map(p => s"${p.id.asString} - ${p.firstName} ${p.lastName}").mkString("\n")
                  McpCallToolResult(List(McpContent("text", if (text.isEmpty) "No people found." else text)))
                }
            case Left(err) => ZIO.fail(new RuntimeException(s"API Error: $err"))
          }
        }

      case "get_person_info" =>
        val id = arguments.get("personId").flatMap(_.as[String].toOption).getOrElse("")
        val req = request(s"/api/person/$id")
        req.send(backend).flatMap { response =>
          response.body match {
            case Right(body) => ZIO.succeed(McpCallToolResult(List(McpContent("text", body))))
            case Left(err) => ZIO.succeed(McpCallToolResult(List(McpContent("text", s"Error: $err")), isError = true))
          }
        }

      case _ => ZIO.fail(new Exception(s"Tool not found: $name"))
    }
  }

  def mcpLoop(backend: WebSocketStreamBackend[Task, ZioStreams])(using config: McpConfig) = {
    val stdinStream = ZStream.fromInputStream(java.lang.System.in)
      .via(ZPipeline.utf8Decode)
      .via(ZPipeline.splitLines)

    stdinStream.mapZIO { line =>
      line.fromJson[McpRequest] match {
        case Left(err) =>
          ZIO.logError(s"Failed to parse request: $err") *>
          ZIO.succeed(None)
        case Right(req) =>
          req.method match {
            case "initialize" =>
              val res = McpInitializeResult("2024-11-05", McpCapabilities(), McpServerInfo("sotohp-mcp", "1.2.0"))
              ZIO.succeed(Some(McpResponse(id = req.id, result = Some(res.toJsonAST.toOption.get))))
            
            case "tools/list" =>
              val res = McpListToolsResult(Tools)
              ZIO.succeed(Some(McpResponse(id = req.id, result = Some(res.toJsonAST.toOption.get))))

            case "tools/call" =>
              req.params.flatMap(_.as[Json.Obj].toOption) match {
                case Some(obj) =>
                  val args = obj.fields.toMap
                  val name = args.get("name").flatMap(_.as[String].toOption).getOrElse("")
                  val toolArgs = args.get("arguments").flatMap(_.as[Json.Obj].toOption).map(_.fields.toMap).getOrElse(Map.empty)
                  handleToolCall(name, toolArgs, backend)
                    .map(res => Some(McpResponse(id = req.id, result = Some(res.toJsonAST.toOption.get))))
                    .catchAll(t => ZIO.succeed(Some(McpResponse(id = req.id, error = Some(McpError(-32000, t.getMessage))))))
                case None =>
                  ZIO.succeed(Some(McpResponse(id = req.id, error = Some(McpError(-32602, "Invalid params")))))
              }

            case "notifications/initialized" =>
              ZIO.succeed(None)

            case _ =>
              ZIO.succeed(Some(McpResponse(id = req.id, error = Some(McpError(-32601, s"Method not found: ${req.method}")))))
          }
      }
    }.collectSome.mapZIO { res =>
      val out = res.toJson
      ZIO.attempt(java.lang.System.out.println(out)) *>
      ZIO.attempt(java.lang.System.out.flush())
    }.runDrain
  }

  override def run = {
    val program = for {
      _       <- ZIO.logInfo("Sotohp MCP Server (API-based) starting...")
      config  <- ZIO.service[McpConfig]
      backend <- HttpClientZioBackend()
      _       <- mcpLoop(backend)(using config)
    } yield ()

    program
      .provide(McpConfig.live)
      .catchAll { err =>
        ZIO.logError(s"MCP Server failed: $err") *> ZIO.succeed(ExitCode.failure)
      }.as(ExitCode.success)
  }
}