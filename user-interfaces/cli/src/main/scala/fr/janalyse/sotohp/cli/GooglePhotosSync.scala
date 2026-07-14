package fr.janalyse.sotohp.cli

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import fr.janalyse.sotohp.media.imaging.BasicImaging
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.search.SearchService
import fr.janalyse.sotohp.service.MediaService
import org.apache.commons.imaging.Imaging
import org.apache.commons.imaging.formats.jpeg.JpegImageMetadata
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter
import org.apache.commons.imaging.formats.tiff.constants.{ExifTagConstants, TiffTagConstants}
import sttp.client4.*
import sttp.client4.httpclient.zio.HttpClientZioBackend
import zio.*
import zio.lmdb.LMDB
import zio.stream.ZSink

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.{ServerSocket, URLDecoder, URLEncoder}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path, Paths}
import scala.io.AnsiColor.*
import scala.util.control.NonFatal

/** Mirror sotohp portfolios to Google Photos albums (one-way, sotohp => Google Photos).
  *
  * For each portfolio, an album with the same name is created in Google Photos (if not already created by a
  * previous run) and every portfolio asset not yet uploaded is uploaded and appended to it. The mapping
  * portfolio->album and originalId->googleMediaItemId is persisted locally, so re-runs are incremental and
  * upload each asset only once.
  *
  * Descriptions are synchronized too :
  *   - asset descriptions are set on the Google Photos media items at upload time, and later description
  *     changes in sotohp are pushed on the next run (mediaItems.patch, only possible on app-created items);
  *   - Google Photos albums have no description field, so the portfolio description is pushed as a *text
  *     enrichment* (a text block at the top of the album). The API cannot edit or remove enrichments : when
  *     the portfolio description changes, a new text block is added, the previous one stays.
  *
  * Croppings (`selectedBox` on an asset) are synchronized as well : the crop is rendered at full resolution
  * from the original (after the same orientation correction the normalized rendition gets, since the box is
  * defined against it), the EXIF metadata of the original (shooting date, GPS, ...) is copied into the
  * rendered JPEG so Google Photos files it at the right point in its timeline. When a crop box is later
  * added, changed or removed in sotohp, the previously uploaded render is removed from the album and the
  * new version is uploaded (the old render stays in the Google Photos library : the API cannot delete).
  * Crops on videos are not supported and the full video is uploaded instead.
  *
  * Ordering : albums mirror the portfolio order (oldest media first). Google Photos albums have no
  * "sort by date" through the API, only a manual order where added items are appended at the end : when the
  * album order diverges from the portfolio order (incremental additions, albums synced by an older version
  * of this tool), the items are detached and re-appended in the wanted order (they just move within the
  * album, nothing is re-uploaded).
  *
  * Changes done on the Google Photos side are repaired as well (one-way mirror : sotohp wins) : on each run
  * the actual album content is checked, items manually removed from the album are re-attached (the media is
  * still in the library, nothing is re-uploaded), items whose media was deleted from the library are
  * re-uploaded, and a manual re-ordering of the album is overwritten.
  *
  * Google Photos API constraints to be aware of:
  *   - the API only allows writing to albums *created by this application*: syncing into a pre-existing album
  *     that was created manually in Google Photos is not possible;
  *   - media items can never be deleted from the Google Photos library through the API. With `--prune`, assets
  *     removed from a portfolio are removed *from the album*, but the uploaded photos remain in the library;
  *   - uploads count against the Google account storage quota, at original quality.
  *
  * One-time Google Cloud setup:
  *   1. create a project on https://console.cloud.google.com and enable the "Photos Library API",
  *   2. create OAuth credentials of type "Desktop app" and export them as environment variables
  *      SOTOHP_GOOGLE_CLIENT_ID and SOTOHP_GOOGLE_CLIENT_SECRET,
  *   3. add your Google account as a test user of the OAuth consent screen (testing mode is fine for
  *      personal use, but Google then expires refresh tokens after 7 days; publishing the consent screen
  *      "in production" - even unverified - avoids that).
  *
  * On first run the tool opens an OAuth consent URL (to paste in a browser) and captures the authorization
  * code through a temporary local HTTP listener; the obtained refresh token is stored with the sync state in
  * `~/.sotohp/google-photos-sync.json` (override with SOTOHP_GOOGLE_SYNC_STATE).
  *
  * Options (all optional):
  *   - `--execute` / `-f`          apply the synchronization (default: dry-run, report only)
  *   - `--portfolio=<substring>`   only sync portfolios whose name contains this (case-insensitive) substring
  *   - `--prune`                   also remove from albums the items whose asset was removed from the portfolio
  *   - `--refresh-descriptions`    push every (non-empty) media description again, even those believed in sync
  *                                 (repair tool, e.g. after Google dropped descriptions on deduplicated uploads)
  *   - `--retry-unmanageable`      try again the medias flagged as out of the app control (a media deleted then
  *                                 restored from the Google Photos trash gets deduplicated by Google to an item
  *                                 the API refuses to manage : arrange/describe/remove all fail on it; to fully
  *                                 repair such a media, delete it in Google Photos AND empty the trash, then
  *                                 rerun the sync with this option so it gets re-uploaded as a fresh item)
  *
  * Examples:
  *   mill --no-server user-interfaces.cli.runMain fr.janalyse.sotohp.cli.GooglePhotosSync
  *   mill --no-server user-interfaces.cli.runMain fr.janalyse.sotohp.cli.GooglePhotosSync --portfolio=vacations --execute
  *   mill --no-server user-interfaces.cli.runMain fr.janalyse.sotohp.cli.GooglePhotosSync --execute --prune
  */
object GooglePhotosSync extends CommonsCLI {

  override def run =
    logic
      .provideSome[ZIOAppArgs](
        LMDB.live,
        SearchService.live,
        MediaService.live,
        Scope.default
      )

  // -------------------------------------------------------------------------------------------------------------------
  // Local persistent state : OAuth refresh token + what has already been uploaded where
  // -------------------------------------------------------------------------------------------------------------------

  private case class PortfolioSyncState(
    albumId: String,
    albumTitle: String,
    uploaded: Map[String, String],                 // originalId -> google mediaItem id
    descriptions: Map[String, String] = Map.empty, // originalId -> last description pushed to Google Photos
    albumDescription: Option[String] = None,       // last portfolio description pushed as an album text enrichment
    crops: Map[String, String] = Map.empty,        // originalId -> signature of the crop box the upload was rendered with
    albumOrder: List[String] = Nil,                // google mediaItem ids in the order the album was last arranged (portfolio order)
    unmanageable: Map[String, String] = Map.empty  // originalId -> mediaItem id of medias out of the app control (upload
                                                   // deduplicated against a library photo not created by the app, e.g. one
                                                   // restored from the trash) : the API refuses to arrange/describe those,
                                                   // they are left untouched (see --retry-unmanageable)
  )

  private case class SyncState(
    refreshToken: Option[String] = None,
    portfolios: Map[String, PortfolioSyncState] = Map.empty // portfolioId -> album sync state
  )

  private given JsonValueCodec[SyncState] = JsonCodecMaker.make(CodecMakerConfig.withTransientDefault(false).withTransientEmpty(false))

  private def stateFilePath: Path =
    sys.env.get("SOTOHP_GOOGLE_SYNC_STATE").map(Paths.get(_)).getOrElse(Paths.get(sys.props("user.home"), ".sotohp", "google-photos-sync.json"))

  private def stateLoad: Task[SyncState] = ZIO.attemptBlocking {
    val path = stateFilePath
    if (Files.exists(path)) readFromArray(Files.readAllBytes(path)) else SyncState()
  }

  private def stateSave(state: SyncState): Task[Unit] = ZIO.attemptBlocking {
    val path = stateFilePath
    Files.createDirectories(path.getParent)
    Files.write(path, writeToArray(state))
    // the file contains the OAuth refresh token, keep it private
    try Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
    catch { case _: UnsupportedOperationException => () }
  }

  // -------------------------------------------------------------------------------------------------------------------
  // Google OAuth2 for installed applications (loopback redirect flow)
  // -------------------------------------------------------------------------------------------------------------------

  private val oauthScopes = List(
    "https://www.googleapis.com/auth/photoslibrary.appendonly",
    "https://www.googleapis.com/auth/photoslibrary.edit.appcreateddata",
    "https://www.googleapis.com/auth/photoslibrary.readonly.appcreateddata"
  )

  private case class OAuthTokenResponse(
    access_token: String,
    expires_in: Long,
    refresh_token: Option[String] = None
  )
  private given JsonValueCodec[OAuthTokenResponse] = JsonCodecMaker.make

  private case class OAuthConfig(clientId: String, clientSecret: String)

  private def oauthConfig: Task[OAuthConfig] = {
    val result = for {
      clientId     <- sys.env.get("SOTOHP_GOOGLE_CLIENT_ID")
      clientSecret <- sys.env.get("SOTOHP_GOOGLE_CLIENT_SECRET")
    } yield OAuthConfig(clientId, clientSecret)
    ZIO
      .fromOption(result)
      .orElseFail(Exception("SOTOHP_GOOGLE_CLIENT_ID and SOTOHP_GOOGLE_CLIENT_SECRET environment variables are required (Google Cloud OAuth 'Desktop app' credentials)"))
  }

  /** Wait on the given local server socket for the browser redirect and extract the `code` query parameter. */
  private def awaitAuthorizationCode(server: ServerSocket): Task[String] = ZIO.attemptBlocking {
    var code: Option[String] = None
    while (code.isEmpty) {
      val socket      = server.accept()
      try {
        val requestLine = scala.io.Source.fromInputStream(socket.getInputStream, "US-ASCII").getLines().nextOption().getOrElse("")
        // requestLine looks like : GET /?code=4/xxxx&scope=... HTTP/1.1
        val params      = requestLine.split(" ").lift(1).flatMap(p => Option(p.dropWhile(_ != '?')).filter(_.nonEmpty).map(_.drop(1))).getOrElse("")
        val decoded     = params
          .split("&")
          .toList
          .flatMap(kv =>
            kv.split("=", 2) match {
              case Array(k, v) => Some(k -> URLDecoder.decode(v, UTF_8))
              case _           => None
            }
          )
          .toMap
        val (status, message) = decoded match {
          case p if p.contains("code")  => ("200 OK", "Authorization received, you can close this page and go back to sotohp.")
          case p if p.contains("error") => ("400 Bad Request", s"Authorization failed : ${p("error")}")
          case _                        => ("404 Not Found", "Ignored")
        }
        val body              = s"<html><body><h1>sotohp</h1><p>$message</p></body></html>"
        val response          = s"HTTP/1.1 $status\r\nContent-Type: text/html\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
        socket.getOutputStream.write(response.getBytes(UTF_8))
        socket.getOutputStream.flush()
        decoded.get("error").foreach(err => throw Exception(s"OAuth authorization failed : $err"))
        code = decoded.get("code")
      } finally socket.close()
    }
    code.get
  }

  private def oauthTokenRequest(backend: Backend[Task], form: Map[String, String]): Task[OAuthTokenResponse] =
    basicRequest
      .post(uri"https://oauth2.googleapis.com/token")
      .body(form)
      .response(asStringAlways)
      .send(backend)
      .flatMap(response =>
        if (response.code.isSuccess) ZIO.attempt(readFromString[OAuthTokenResponse](response.body))
        else ZIO.fail(Exception(s"OAuth token request failed (${response.code}) : ${response.body}"))
      )

  /** Interactive first-time authorization : print the consent URL, wait for the browser redirect. */
  private def oauthAuthorizeInteractively(backend: Backend[Task], config: OAuthConfig): Task[OAuthTokenResponse] =
    ZIO.acquireReleaseWith(ZIO.attempt(ServerSocket(0)))(server => ZIO.succeed(server.close())) { server =>
      val redirectUri = s"http://localhost:${server.getLocalPort}"
      val authUrl     =
        "https://accounts.google.com/o/oauth2/v2/auth" +
          s"?client_id=${URLEncoder.encode(config.clientId, UTF_8)}" +
          s"&redirect_uri=${URLEncoder.encode(redirectUri, UTF_8)}" +
          "&response_type=code" +
          s"&scope=${URLEncoder.encode(oauthScopes.mkString(" "), UTF_8)}" +
          "&access_type=offline" +
          "&prompt=consent"
      for {
        _      <- Console.printLine(s"${YELLOW}Open the following URL in your browser to authorize sotohp on your Google Photos account :$RESET\n$authUrl")
        code   <- awaitAuthorizationCode(server)
        tokens <- oauthTokenRequest(
                    backend,
                    Map(
                      "grant_type"    -> "authorization_code",
                      "code"          -> code,
                      "client_id"     -> config.clientId,
                      "client_secret" -> config.clientSecret,
                      "redirect_uri"  -> redirectUri
                    )
                  )
      } yield tokens
    }

  private def oauthRefresh(backend: Backend[Task], config: OAuthConfig, refreshToken: String): Task[OAuthTokenResponse] =
    oauthTokenRequest(
      backend,
      Map(
        "grant_type"    -> "refresh_token",
        "refresh_token" -> refreshToken,
        "client_id"     -> config.clientId,
        "client_secret" -> config.clientSecret
      )
    )

  /** Access tokens expire after ~1h and a big first sync can take longer : dispense them through a Ref and
    * refresh automatically a bit before expiry.
    */
  private class AccessTokenProvider(backend: Backend[Task], config: OAuthConfig, refreshToken: String, cache: Ref[Option[(String, Long)]]) {
    val current: Task[String] =
      for {
        now    <- Clock.instant.map(_.getEpochSecond)
        cached <- cache.get
        token  <- cached match {
                    case Some((token, expiresAt)) if expiresAt - now > 60 => ZIO.succeed(token)
                    case _                                                =>
                      oauthRefresh(backend, config, refreshToken)
                        .tap(response => cache.set(Some((response.access_token, now + response.expires_in))))
                        .map(_.access_token)
                  }
      } yield token
  }

  // -------------------------------------------------------------------------------------------------------------------
  // Google Photos Library API client (only the few calls the synchronization needs)
  // -------------------------------------------------------------------------------------------------------------------

  private val photosApiBase = "https://photoslibrary.googleapis.com/v1"

  private case class GoogleAlbum(id: String, title: Option[String] = None)
  private case class AlbumsListResponse(albums: Option[List[GoogleAlbum]] = None, nextPageToken: Option[String] = None)
  private case class AlbumCreateRequest(album: AlbumTitle)
  private case class AlbumTitle(title: String)
  private case class SimpleMediaItem(fileName: String, uploadToken: String)
  private case class NewMediaItem(description: Option[String], simpleMediaItem: SimpleMediaItem)
  private case class BatchCreateRequest(albumId: String, newMediaItems: List[NewMediaItem])
  private case class ResultStatus(message: Option[String] = None, code: Option[Int] = None)
  private case class GoogleMediaItem(id: String, description: Option[String] = None)
  private case class NewMediaItemResult(uploadToken: Option[String] = None, status: Option[ResultStatus] = None, mediaItem: Option[GoogleMediaItem] = None)
  private case class BatchCreateResponse(newMediaItemResults: Option[List[NewMediaItemResult]] = None)
  private case class BatchRemoveRequest(mediaItemIds: List[String])
  private case class TextEnrichment(text: String)
  private case class NewEnrichmentItem(textEnrichment: TextEnrichment)
  private case class AlbumPosition(position: String)
  private case class AddEnrichmentRequest(newEnrichmentItem: NewEnrichmentItem, albumPosition: AlbumPosition)
  private case class MediaItemDescriptionPatch(description: String)
  private case class MediaItemsSearchRequest(albumId: String, pageSize: Int, pageToken: Option[String] = None)
  private case class MediaItemsSearchResponse(mediaItems: Option[List[GoogleMediaItem]] = None, nextPageToken: Option[String] = None)
  private case class IgnoredResponse()

  private given JsonValueCodec[AlbumsListResponse]         = JsonCodecMaker.make
  private given JsonValueCodec[AlbumCreateRequest]         = JsonCodecMaker.make
  private given JsonValueCodec[GoogleAlbum]                = JsonCodecMaker.make
  private given JsonValueCodec[BatchCreateRequest]         = JsonCodecMaker.make
  private given JsonValueCodec[BatchCreateResponse]        = JsonCodecMaker.make
  private given JsonValueCodec[BatchRemoveRequest]         = JsonCodecMaker.make
  private given JsonValueCodec[AddEnrichmentRequest]       = JsonCodecMaker.make
  private given JsonValueCodec[MediaItemDescriptionPatch]  = JsonCodecMaker.make
  private given JsonValueCodec[MediaItemsSearchRequest]    = JsonCodecMaker.make
  private given JsonValueCodec[MediaItemsSearchResponse]   = JsonCodecMaker.make
  private given JsonValueCodec[IgnoredResponse]            = JsonCodecMaker.make

  private case class QuotaExceededError(message: String) extends Exception(message)

  private class GooglePhotosClient(backend: Backend[Task], tokens: AccessTokenProvider) {

    /** The API caps write requests per minute and per day : on a per-minute quota error (429) pause until
      * the quota window refills and retry, instead of failing the whole synchronization.
      */
    private def withQuotaRetry[A](call: Task[A], attempts: Int = 8): Task[A] =
      call.catchSome {
        case QuotaExceededError(_) if attempts > 0 =>
          ZIO.logWarning("Google Photos API write quota reached, pausing 65s before retrying") *>
            ZIO.sleep(65.seconds) *> withQuotaRetry(call, attempts - 1)
      }

    private def jsonCall[I: JsonValueCodec, O: JsonValueCodec](method: String, path: String, input: Option[I]): Task[O] = {
      val once =
        for {
          accessToken <- tokens.current
          request      = {
            // Uri.unsafeParse because the sttp uri"" interpolator would percent-encode the query part of path
            val endpoint = sttp.model.Uri.unsafeParse(photosApiBase + path)
            val base     = method match {
              case "GET"   => basicRequest.get(endpoint)
              case "PATCH" => basicRequest.patch(endpoint)
              case _       => basicRequest.post(endpoint)
            }
            input
              .fold(base)(in => base.body(writeToString(in)).contentType("application/json"))
              .auth.bearer(accessToken)
              .response(asStringAlways)
          }
          response    <- request.send(backend)
          output      <- if (response.code.isSuccess) ZIO.attempt(readFromString[O](response.body))
                         else if (response.code.code == 429) ZIO.fail(QuotaExceededError(s"$method $path"))
                         else ZIO.fail(Exception(s"Google Photos API $method $path failed (${response.code}) : ${response.body}"))
        } yield output
      withQuotaRetry(once)
    }

    /** List all the albums previously created by this application. */
    def appCreatedAlbums: Task[List[GoogleAlbum]] = {
      def loop(pageToken: Option[String], accumulated: List[GoogleAlbum]): Task[List[GoogleAlbum]] =
        jsonCall[BatchRemoveRequest, AlbumsListResponse]("GET", s"/albums?pageSize=50&excludeNonAppCreatedData=true${pageToken.fold("")(t => s"&pageToken=$t")}", None)
          .flatMap { response =>
            val all = accumulated ++ response.albums.getOrElse(Nil)
            response.nextPageToken.filter(_.nonEmpty) match {
              case Some(next) => loop(Some(next), all)
              case None       => ZIO.succeed(all)
            }
          }
      loop(None, Nil)
    }

    def albumCreate(title: String): Task[GoogleAlbum] =
      jsonCall[AlbumCreateRequest, GoogleAlbum]("POST", "/albums", Some(AlbumCreateRequest(AlbumTitle(title))))

    /** Upload the raw bytes of a media, returns the upload token to use with [[mediaItemsBatchCreate]]. */
    def uploadBytes(bytes: Array[Byte], mimeType: String): Task[String] = {
      val once =
        for {
          accessToken <- tokens.current
          response    <- basicRequest
                           .post(sttp.model.Uri.unsafeParse(s"$photosApiBase/uploads"))
                           .body(bytes)
                           .contentType("application/octet-stream")
                           .header("X-Goog-Upload-Content-Type", mimeType)
                           .header("X-Goog-Upload-Protocol", "raw")
                           .auth.bearer(accessToken)
                           .response(asStringAlways)
                           .send(backend)
          token       <- if (response.code.isSuccess) ZIO.succeed(response.body)
                         else if (response.code.code == 429) ZIO.fail(QuotaExceededError("POST /uploads"))
                         else ZIO.fail(Exception(s"Google Photos upload failed (${response.code}) : ${response.body}"))
        } yield token
      withQuotaRetry(once)
    }

    /** Attach up to 50 uploaded medias to an album, returns the created media items (in input order).
      * Beware : when the uploaded bytes are identical to a media already in the library (even trashed),
      * Google returns that *existing* item instead of creating one, and the provided description is
      * silently ignored - hence the returned description, so callers can detect it.
      */
    def mediaItemsBatchCreate(albumId: String, items: List[NewMediaItem]): Task[List[Option[GoogleMediaItem]]] =
      jsonCall[BatchCreateRequest, BatchCreateResponse]("POST", "/mediaItems:batchCreate", Some(BatchCreateRequest(albumId, items)))
        .map(response => response.newMediaItemResults.getOrElse(Nil).map(_.mediaItem))

    def mediaItemsBatchRemove(albumId: String, mediaItemIds: List[String]): Task[Unit] =
      jsonCall[BatchRemoveRequest, AlbumsListResponse]("POST", s"/albums/$albumId:batchRemoveMediaItems", Some(BatchRemoveRequest(mediaItemIds)))
        .unit

    /** Append existing (app-created) media items at the end of an album. */
    def mediaItemsBatchAdd(albumId: String, mediaItemIds: List[String]): Task[Unit] =
      jsonCall[BatchRemoveRequest, IgnoredResponse]("POST", s"/albums/$albumId:batchAddMediaItems", Some(BatchRemoveRequest(mediaItemIds)))
        .unit

    /** The ids of the (app-created) media items currently in an album, in album order. */
    def albumMediaItemIds(albumId: String): Task[List[String]] = {
      def loop(pageToken: Option[String], accumulated: List[String]): Task[List[String]] =
        jsonCall[MediaItemsSearchRequest, MediaItemsSearchResponse]("POST", "/mediaItems:search", Some(MediaItemsSearchRequest(albumId, 100, pageToken)))
          .flatMap { response =>
            val all = accumulated ++ response.mediaItems.getOrElse(Nil).map(_.id)
            response.nextPageToken.filter(_.nonEmpty) match {
              case Some(next) => loop(Some(next), all)
              case None       => ZIO.succeed(all)
            }
          }
      loop(None, Nil)
    }

    /** Albums have no description field : a text enrichment (text block inside the album) is the closest
      * equivalent. Enrichments can never be edited nor removed through the API.
      */
    def albumAddText(albumId: String, text: String): Task[Unit] =
      jsonCall[AddEnrichmentRequest, IgnoredResponse](
        "POST",
        s"/albums/$albumId:addEnrichment",
        Some(AddEnrichmentRequest(NewEnrichmentItem(TextEnrichment(text)), AlbumPosition("FIRST_IN_ALBUM")))
      ).unit

    /** Update the description of an (app-created) media item. */
    def mediaItemSetDescription(mediaItemId: String, description: String): Task[Unit] =
      jsonCall[MediaItemDescriptionPatch, IgnoredResponse](
        "PATCH",
        s"/mediaItems/$mediaItemId?updateMask=description",
        Some(MediaItemDescriptionPatch(description))
      ).unit
  }

  // -------------------------------------------------------------------------------------------------------------------
  // Synchronization logic
  // -------------------------------------------------------------------------------------------------------------------

  private val batchCreateMaxSize = 50 // Google Photos API hard limit for mediaItems:batchCreate and batchRemove

  private def guessMimeType(fileName: String): String = {
    val extension = fileName.reverse.takeWhile(_ != '.').reverse.toLowerCase
    extension match {
      case "jpg" | "jpeg" => "image/jpeg"
      case "png"          => "image/png"
      case "gif"          => "image/gif"
      case "webp"         => "image/webp"
      case "heic"         => "image/heic"
      case "tif" | "tiff" => "image/tiff"
      case "bmp"          => "image/bmp"
      case "mp4"          => "video/mp4"
      case "mov"          => "video/quicktime"
      case "avi"          => "video/x-msvideo"
      case "mkv"          => "video/x-matroska"
      case _              => "application/octet-stream"
    }
  }

  /** Google Photos media item descriptions are limited to 1000 characters. */
  private def assetDescriptionOf(asset: Asset): Option[String] =
    asset.description.map(_.text.take(1000)).filter(_.nonEmpty)

  private def portfolioDescriptionOf(portfolio: Portfolio): Option[String] =
    portfolio.description.map(_.text.take(1000)).filter(_.nonEmpty)

  /** The portfolio description to push as a new album text block, when it differs from the last pushed one.
    * A removed description yields nothing : enrichments can't be deleted anyway.
    */
  private def albumDescriptionChange(portfolio: Portfolio, state: PortfolioSyncState): Option[String] =
    portfolioDescriptionOf(portfolio).filterNot(text => state.albumDescription.contains(text))

  // -------------------------------------------------------------------------------------------------------------------
  // Cropped assets rendering
  // -------------------------------------------------------------------------------------------------------------------

  private val cropJpegQuality = 0.92f

  /** Stable signature of an asset crop box, used to detect crop changes between runs. */
  private def cropSignatureOf(asset: Asset): Option[String] =
    asset.selectedBox
      .filter(box => box.width.value > 0d && box.height.value > 0d)
      .map(box => f"${box.x.value}%.5f,${box.y.value}%.5f,${box.width.value}%.5f,${box.height.value}%.5f")

  private def cropFileNameOf(fileName: String): String = {
    val dot  = fileName.lastIndexOf('.')
    val stem = if (dot > 0) fileName.take(dot) else fileName
    s"$stem.crop.jpg"
  }

  private def toJpegBytes(image: BufferedImage): Array[Byte] = {
    val rgb    = if (image.getType == BufferedImage.TYPE_INT_RGB) image
                 else {
                   val converted = BufferedImage(image.getWidth, image.getHeight, BufferedImage.TYPE_INT_RGB)
                   val graphics  = converted.createGraphics
                   try { graphics.drawImage(image, 0, 0, java.awt.Color.WHITE, null); converted }
                   finally graphics.dispose()
                 }
    val writer = javax.imageio.ImageIO.getImageWritersByFormatName("jpeg").next()
    val params = writer.getDefaultWriteParam
    params.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT)
    params.setCompressionQuality(cropJpegQuality)
    val bytes  = ByteArrayOutputStream()
    val output = javax.imageio.ImageIO.createImageOutputStream(bytes)
    try {
      writer.setOutput(output)
      writer.write(null, javax.imageio.IIOImage(rgb, null, null), params)
    } finally {
      writer.dispose()
      output.close()
    }
    bytes.toByteArray
  }

  /** Copy the EXIF block of the original JPEG into the rendered crop, so Google Photos keeps the shooting
    * date, the GPS location, the camera info... Best effort : non-JPEG originals or EXIF issues simply
    * yield the crop without metadata (it will then be filed at upload date in the Google Photos timeline).
    */
  private def copyExif(originalPath: Path, croppedJpegBytes: Array[Byte]): Array[Byte] =
    try
      Imaging.getMetadata(originalPath.toFile) match {
        case metadata: JpegImageMetadata if metadata.getExif != null =>
          val outputSet = metadata.getExif.getOutputSet
          outputSet.removeField(TiffTagConstants.TIFF_TAG_ORIENTATION)       // the crop pixels are already upright
          outputSet.removeField(ExifTagConstants.EXIF_TAG_EXIF_IMAGE_WIDTH)  // stale, they describe the original
          outputSet.removeField(ExifTagConstants.EXIF_TAG_EXIF_IMAGE_LENGTH)
          val output = ByteArrayOutputStream()
          ExifRewriter().updateExifMetadataLossless(croppedJpegBytes, output, outputSet)
          output.toByteArray
        case _                                                       => croppedJpegBytes
      }
    catch { case NonFatal(_) => croppedJpegBytes }

  /** Render the crop of an original at full resolution. The crop box is relative (0..1) and defined against
    * the orientation-corrected image (that's what the frontend displays), so the same rotation as the
    * normalized rendition is applied before cropping.
    */
  private def renderCrop(original: Original, box: BoundingBox): Task[Array[Byte]] =
    ZIO.attemptBlocking {
      val loaded  = BasicImaging.load(original.absoluteMediaPath)
      val degrees = original.orientation.map(_.rotationDegrees.toDouble).getOrElse(0d)
      val rotated = BasicImaging.rotate(loaded, degrees)
      val x       = (box.x.value * rotated.getWidth).round.toInt.max(0).min(rotated.getWidth - 1)
      val y       = (box.y.value * rotated.getHeight).round.toInt.max(0).min(rotated.getHeight - 1)
      val width   = (box.width.value * rotated.getWidth).round.toInt.max(1).min(rotated.getWidth - x)
      val height  = (box.height.value * rotated.getHeight).round.toInt.max(1).min(rotated.getHeight - y)
      copyExif(original.absoluteMediaPath, toJpegBytes(rotated.getSubimage(x, y, width, height)))
    }

  /** (originalId, mediaItemId, newDescription) for already-uploaded assets whose description changed in
    * sotohp since it was last pushed. An empty newDescription clears the Google Photos one.
    */
  private def descriptionUpdates(portfolio: Portfolio, state: PortfolioSyncState): List[(String, String, String)] =
    portfolio.assets.flatMap { asset =>
      val originalId = asset.originalId.asString
      state.uploaded.get(originalId).flatMap { mediaItemId =>
        val wanted = assetDescriptionOf(asset).getOrElse("")
        state.descriptions.get(originalId) match {
          case Some(pushed) if pushed != wanted => Some((originalId, mediaItemId, wanted))
          case None if wanted.nonEmpty          => Some((originalId, mediaItemId, wanted)) // state predating description tracking
          case _                                => None
        }
      }
    }

  /** The google mediaItem ids in the order the album should show them : the portfolio asset order (oldest
    * media first, as served by MediaService), restricted to the assets actually uploaded.
    */
  private def desiredAlbumOrder(portfolio: Portfolio, state: PortfolioSyncState): List[String] =
    portfolio.assets.flatMap(asset => state.uploaded.get(asset.originalId.asString))

  /** Detect and repair changes done directly on the Google Photos side (one-way mirror : sotohp wins).
    * The actual album content is fetched : items manually removed from the album are re-attached (the media
    * is still in the library, no re-upload needed), items whose media was deleted from the library are
    * forgotten from the state so the asset is re-uploaded in the same run, and the server-side album order
    * replaces the locally saved one as the basis of the final re-arrangement check (so a manual re-ordering
    * gets overwritten too). Best effort : when the album can't be checked the state is returned untouched.
    * Also returns the purged (originalId -> mediaItem id) pairs : when the incoming re-upload of one of
    * those assets is deduplicated by Google to the very same id, the media is out of the app control and
    * gets flagged unmanageable instead of being re-uploaded forever.
    */
  private def albumDriftRepair(
    client: GooglePhotosClient,
    albumId: String,
    portfolioName: String,
    state: PortfolioSyncState,
    saveState: PortfolioSyncState => Task[Unit]
  ): Task[(PortfolioSyncState, Map[String, String])] = {
    val repair = for {
      serverIds    <- client.albumMediaItemIds(albumId)
      ourIds        = state.uploaded.values.toSet
      serverOrder   = serverIds.filter(ourIds)
      inAlbum       = serverOrder.toSet
      detached      = state.uploaded.filter { case (_, itemId) => !inAlbum(itemId) }
      readded      <- ZIO.foldLeft(detached.values.toList.grouped(batchCreateMaxSize).toList)(List.empty[String]) { (readded, ids) =>
                        client
                          .mediaItemsBatchAdd(albumId, ids)
                          .as(readded ++ ids)
                          // a batch is rejected as a whole when any of its ids is invalid (media deleted from the
                          // library) : retry one by one to isolate the deleted ones
                          .catchAll(_ => ZIO.foldLeft(ids)(readded)((acc, id) => client.mediaItemsBatchAdd(albumId, List(id)).as(acc :+ id).catchAll(_ => ZIO.succeed(acc))))
                      }
      lostItemIds   = detached.values.toSet -- readded
      lostPairs     = detached.filter { case (_, itemId) => lostItemIds(itemId) }
      repaired      = state.copy(
                        uploaded = state.uploaded -- lostPairs.keys,
                        descriptions = state.descriptions -- lostPairs.keys,
                        crops = state.crops -- lostPairs.keys,
                        albumOrder = serverOrder ++ readded
                      )
      _            <- ZIO.when(detached.nonEmpty)(
                        saveState(repaired) *>
                          ZIO.logInfo(
                            s"'$portfolioName' : ${detached.size} items had disappeared from the album on the Google Photos side : " +
                              s"${readded.size} re-attached, ${lostPairs.size} deleted medias to re-upload"
                          )
                      )
    } yield (repaired, lostPairs)
    repair.catchAll(err => ZIO.logWarning(s"'$portfolioName' : couldn't check the album content on the Google Photos side : ${err.getMessage}").as((state, Map.empty)))
  }

  /** Google Photos albums have no "sort by date" through the API : an album only has a manual order and
    * added items land at the end. The album is re-arranged to mirror the portfolio order by detaching our
    * items and re-appending them in the wanted order (media items are untouched, they just move within
    * the album).
    */
  private def reorderAlbum(
    client: GooglePhotosClient,
    albumId: String,
    portfolioName: String,
    state: PortfolioSyncState,
    wantedOrder: List[String],
    saveState: PortfolioSyncState => Task[Unit]
  ): Task[PortfolioSyncState] = {
    val cleared = state.copy(albumOrder = Nil)
    val steps   = for {
      _    <- saveState(cleared) // crash-safe : an interrupted re-arrangement is redone on the next run
      _    <- ZIO.foreachDiscard(wantedOrder.grouped(batchCreateMaxSize).toList) { ids =>
                client
                  .mediaItemsBatchRemove(albumId, ids)
                  // a batch is rejected as a whole when any of its items is not in the album (manual removal,
                  // interrupted previous run) : retry one by one and ignore those failures
                  .catchAll(_ => ZIO.foreachDiscard(ids)(id => client.mediaItemsBatchRemove(albumId, List(id)).ignore))
              }
      appended     <- ZIO.foldLeft(wantedOrder.grouped(batchCreateMaxSize).toList)(List.empty[String]) { (added, ids) =>
                        client
                          .mediaItemsBatchAdd(albumId, ids)
                          .as(added ++ ids)
                          // a batch is rejected as a whole when any of its ids is invalid (media item deleted from
                          // the Google Photos library since) : retry one by one to isolate and drop the invalid ones
                          .catchAll(err =>
                            ZIO.logWarning(s"'$portfolioName' : a batch couldn't be added back as a whole, isolating the failing items : ${err.getMessage}") *>
                              ZIO.foldLeft(ids)(added)((added, id) => client.mediaItemsBatchAdd(albumId, List(id)).as(added :+ id).catchAll(_ => ZIO.succeed(added)))
                          )
                      }
      lostItemIds   = wantedOrder.toSet -- appended
      lostOriginals = state.uploaded.collect { case (originalId, mediaItemId) if lostItemIds.contains(mediaItemId) => originalId }.toSet
      done          = state.copy(
                        uploaded = state.uploaded -- lostOriginals,
                        descriptions = state.descriptions -- lostOriginals,
                        crops = state.crops -- lostOriginals,
                        albumOrder = appended
                      )
      _            <- saveState(done)
      _            <- ZIO.logInfo(s"'$portfolioName' : album re-arranged to portfolio order (${appended.size} items, oldest first)")
      _            <- ZIO.when(lostOriginals.nonEmpty)(
                        ZIO.logWarning(
                          s"'$portfolioName' : ${lostOriginals.size} previously uploaded medias no longer exist on Google Photos" +
                            " (deleted from the library ?), they will be re-uploaded on the next run"
                        )
                      )
    } yield done
    steps.catchAll(err => ZIO.logWarning(s"'$portfolioName' : couldn't re-arrange the album (${err.getMessage}), will retry on the next run").as(cleared))
  }

  /** Find the album for a portfolio : from the saved state first, else by title among app-created albums,
    * else create it (or just announce it in dry-run mode).
    */
  private def resolveAlbum(
    client: GooglePhotosClient,
    portfolio: Portfolio,
    saved: Option[PortfolioSyncState],
    executeMode: Boolean
  ): Task[Option[GoogleAlbum]] =
    saved match {
      case Some(state) => ZIO.some(GoogleAlbum(state.albumId, Some(state.albumTitle)))
      case None        =>
        client.appCreatedAlbums
          .map(_.find(_.title.contains(portfolio.name.text)))
          .flatMap {
            case Some(album)          => ZIO.logInfo(s"Reusing app-created album '${portfolio.name.text}'").as(Some(album))
            case None if executeMode  => client.albumCreate(portfolio.name.text).asSome <* ZIO.logInfo(s"Created album '${portfolio.name.text}'")
            case None                 => ZIO.logInfo(s"Would create album '${portfolio.name.text}'").as(None)
          }
    }

  /** Upload a single asset (the crop render when the asset has a crop box, the untouched original
    * otherwise) and return its (originalId, NewMediaItem) or None when the asset must be skipped.
    */
  private def uploadAsset(client: GooglePhotosClient, asset: Asset) = {
    val step = for {
      mediaTuple                   <- MediaService.mediaGet(asset.originalId).some.mapError(_ => Exception(s"No media found for original ${asset.originalId.asString}"))
      original                      = mediaTuple.media.original
      fileName                      = original.mediaPath.fileName
      cropBox                       = if (original.kind == MediaKind.Photo) asset.selectedBox.filter(_ => cropSignatureOf(asset).isDefined) else None
      payload                      <- cropBox match {
                                        case Some(box) =>
                                          renderCrop(original, box)
                                            .mapBoth(err => Exception(s"Couldn't render the crop of $fileName : $err"), bytes => (cropFileNameOf(fileName), bytes, "image/jpeg"))
                                        case None      =>
                                          MediaService
                                            .mediaOriginalRead(mediaTuple.key)
                                            .run(ZSink.collectAll)
                                            .mapBoth(issue => Exception(s"Couldn't read $fileName : $issue"), chunk => (fileName, chunk.toArray, guessMimeType(fileName)))
                                      }
      (uploadName, bytes, mimeType) = payload
      token                        <- client.uploadBytes(bytes, mimeType)
    } yield asset.originalId.asString -> NewMediaItem(assetDescriptionOf(asset), SimpleMediaItem(uploadName, token))
    step
      .map(Some(_))
      .catchAll(err => ZIO.logWarning(s"Skipping asset ${asset.originalId.asString} : ${err.getMessage}").as(None))
  }

  /** Synchronize one portfolio, returns the updated sync state for it (unchanged in dry-run mode). */
  private def synchronizePortfolio(
    client: GooglePhotosClient,
    portfolio: Portfolio,
    savedRaw: Option[PortfolioSyncState],
    executeMode: Boolean,
    pruneMode: Boolean,
    refreshDescMode: Boolean,
    retryUnmanageableMode: Boolean,
    saveState: PortfolioSyncState => Task[Unit]
  ) = {
    // --retry-unmanageable : forget the out-of-app-control markers so those medias are attempted again
    val saved             = if (retryUnmanageableMode) savedRaw.map(_.copy(unmanageable = Map.empty)) else savedRaw
    // --refresh-descriptions : forget what was pushed so every non-empty description is pushed again
    def effectiveForDescriptions(state: PortfolioSyncState) =
      if (refreshDescMode) state.copy(descriptions = Map.empty) else state
    val alreadyUploaded   = saved.map(_.uploaded).getOrElse(Map.empty)
    val savedCrops        = saved.map(_.crops).getOrElse(Map.empty)
    val unmanageableIds   = saved.map(_.unmanageable.keySet).getOrElse(Set.empty)
    val currentIds        = portfolio.assets.map(_.originalId.asString).toSet
    val missingAssets     = portfolio.assets.filterNot { asset =>
      val id = asset.originalId.asString
      alreadyUploaded.contains(id) || unmanageableIds.contains(id)
    }
    // already uploaded assets whose crop box was added/changed/removed since : re-render and re-upload
    val cropChangedAssets = portfolio.assets.filter { asset =>
      val id = asset.originalId.asString
      alreadyUploaded.contains(id) && savedCrops.getOrElse(id, "") != cropSignatureOf(asset).getOrElse("")
    }
    val removedEntries    = alreadyUploaded.view.filterKeys(id => !currentIds.contains(id)).toMap
    for {
      _     <- Console.printLine(
                 s"$CYAN- portfolio '${portfolio.name.text}' : ${portfolio.assets.size} assets, " +
                   s"${missingAssets.size} to upload, ${cropChangedAssets.size} crop changes, ${removedEntries.size} removed since last sync$RESET"
               )
      album <- resolveAlbum(client, portfolio, saved, executeMode)
      state <- (album, executeMode) match {
                 case (Some(album), true) =>
                   val initial = saved.getOrElse(PortfolioSyncState(album.id, album.title.getOrElse(portfolio.name.text), alreadyUploaded))
                   for {
                     withDesc <- albumDescriptionChange(portfolio, initial) match {
                                   case None       => ZIO.succeed(initial)
                                   case Some(text) =>
                                     client
                                       .albumAddText(album.id, text)
                                       .as(initial.copy(albumDescription = Some(text)))
                                       .tap(state => saveState(state) *> ZIO.logInfo(s"'${portfolio.name.text}' : portfolio description added as an album text block"))
                                       .catchAll(err => ZIO.logWarning(s"'${portfolio.name.text}' : couldn't add the album description : ${err.getMessage}").as(initial))
                                 }
                     driftResult          <- albumDriftRepair(client, album.id, portfolio.name.text, withDesc, saveState)
                     (checked, lostPairs)  = driftResult
                     // recomputed on the repaired state : deleted-on-google medias are re-uploaded in this same run
                     missingNow     = portfolio.assets.filterNot { asset =>
                                        val id = asset.originalId.asString
                                        checked.uploaded.contains(id) || checked.unmanageable.contains(id)
                                      }
                     cropChangedNow = portfolio.assets.filter { asset =>
                                        val id = asset.originalId.asString
                                        checked.uploaded.contains(id) && checked.crops.getOrElse(id, "") != cropSignatureOf(asset).getOrElse("")
                                      }
                     recropped <- if (cropChangedNow.isEmpty) ZIO.succeed(checked)
                                  else {
                                    val changedIds   = cropChangedNow.map(_.originalId.asString)
                                    val staleItemIds = changedIds.flatMap(checked.uploaded.get)
                                    ZIO
                                      .foreachDiscard(staleItemIds.grouped(batchCreateMaxSize).toList)(ids => client.mediaItemsBatchRemove(album.id, ids))
                                      .catchAll(err => ZIO.logWarning(s"'${portfolio.name.text}' : couldn't remove the outdated renders from the album (${err.getMessage}), duplicates may appear"))
                                      .as(
                                        checked.copy(
                                          uploaded = checked.uploaded -- changedIds,
                                          descriptions = checked.descriptions -- changedIds,
                                          crops = checked.crops -- changedIds,
                                          albumOrder = checked.albumOrder.filterNot(staleItemIds.toSet)
                                        )
                                      )
                                      .tap(saveState)
                                      .tap(_ => ZIO.logInfo(s"'${portfolio.name.text}' : ${cropChangedNow.size} crop changes, outdated renders removed from the album (they stay in the Google Photos library)"))
                                  }
                     synced   <- ZIO.foldLeft((missingNow ++ cropChangedNow).grouped(batchCreateMaxSize).toList)(recropped) { (state, assetsBatch) =>
                                   for {
                                     uploads       <- ZIO.foreach(assetsBatch)(asset => uploadAsset(client, asset)).map(_.flatten)
                                     createdItems  <- if (uploads.isEmpty) ZIO.succeed(Nil)
                                                      else client.mediaItemsBatchCreate(album.id, uploads.map { case (_, item) => item })
                                     allConfirmed   = uploads.zipAll(createdItems, ("", NewMediaItem(None, SimpleMediaItem("", ""))), None).collect {
                                                        case ((originalId, sent), Some(created)) if originalId.nonEmpty => (originalId, sent, created)
                                                      }
                                     // a re-upload deduplicated by Google to the very id that was just purged means the
                                     // media exists in the library outside of the app control (e.g. restored from the
                                     // trash) : flag it unmanageable once for all instead of re-uploading it on every run
                                     (rejected, confirmed) = allConfirmed.partition { case (originalId, _, created) => lostPairs.get(originalId).contains(created.id) }
                                     _             <- ZIO.when(rejected.nonEmpty)(
                                                        ZIO.logWarning(
                                                          s"'${portfolio.name.text}' : ${rejected.size} medias exist on Google Photos outside of the app control" +
                                                            " (photo already in the library or restored from the trash) : the API refuses to manage them," +
                                                            " they are flagged unmanageable and left untouched (see --retry-unmanageable)"
                                                        )
                                                      )
                                     cropByOriginal = assetsBatch.map(asset => asset.originalId.asString -> cropSignatureOf(asset).getOrElse("")).toMap
                                     nextState      = state.copy(
                                                        unmanageable = state.unmanageable ++ rejected.map { case (originalId, _, created) => originalId -> created.id },
                                                        uploaded = state.uploaded ++ confirmed.map { case (originalId, _, created) => originalId -> created.id },
                                                        // a description is recorded as pushed only when Google took it : an upload deduplicated
                                                        // against an already existing media item keeps the existing item description, the
                                                        // description patch phase below then fixes it
                                                        descriptions = state.descriptions ++ confirmed.collect {
                                                          case (originalId, sent, created) if created.description.getOrElse("") == sent.description.getOrElse("") =>
                                                            originalId -> sent.description.getOrElse("")
                                                        },
                                                        crops = state.crops ++ confirmed.flatMap { case (originalId, _, _) =>
                                                          val signature = cropByOriginal.getOrElse(originalId, "")
                                                          Option.when(signature.nonEmpty)(originalId -> signature)
                                                        },
                                                        albumOrder = state.albumOrder ++ confirmed.map { case (_, _, created) => created.id }
                                                      )
                                     _             <- saveState(nextState) // crash-safe : persist progress after every batch
                                     _             <- ZIO.logInfo(s"'${portfolio.name.text}' : ${confirmed.size}/${assetsBatch.size} medias uploaded and added to the album")
                                   } yield nextState
                                 }
                     updates   = descriptionUpdates(portfolio, effectiveForDescriptions(synced))
                     patched  <- ZIO.foldLeft(updates)(synced) { case (state, (originalId, mediaItemId, description)) =>
                                   client
                                     .mediaItemSetDescription(mediaItemId, description)
                                     .as(state.copy(descriptions = state.descriptions + (originalId -> description)))
                                     .catchAll(err => ZIO.logWarning(s"Couldn't update the description of media item $mediaItemId : ${err.getMessage}").as(state))
                                 }
                     _        <- ZIO.when(updates.nonEmpty)(saveState(patched) *> ZIO.logInfo(s"'${portfolio.name.text}' : ${updates.size} media descriptions updated"))
                     pruned   <- if (pruneMode && removedEntries.nonEmpty) {
                                   ZIO
                                     .foreachDiscard(removedEntries.values.toList.grouped(batchCreateMaxSize).toList)(ids => client.mediaItemsBatchRemove(album.id, ids))
                                     .as(
                                       patched.copy(
                                         uploaded = patched.uploaded -- removedEntries.keys,
                                         descriptions = patched.descriptions -- removedEntries.keys,
                                         crops = patched.crops -- removedEntries.keys,
                                         albumOrder = patched.albumOrder.filterNot(removedEntries.values.toSet)
                                       )
                                     )
                                     .tap(_ => ZIO.logInfo(s"'${portfolio.name.text}' : ${removedEntries.size} items removed from the album (still in the Google Photos library)"))
                                 } else ZIO.succeed(patched)
                     ordered  <- {
                                   val wantedOrder = desiredAlbumOrder(portfolio, pruned)
                                   if (wantedOrder == pruned.albumOrder) ZIO.succeed(pruned)
                                   else reorderAlbum(client, album.id, portfolio.name.text, pruned, wantedOrder, saveState)
                                 }
                     _        <- saveState(ordered)
                   } yield Some(ordered)
                 case _                   =>
                   val descriptionCount = saved.fold(0)(state => descriptionUpdates(portfolio, effectiveForDescriptions(state)).size)
                   val albumDescPending = saved match {
                     case Some(state) => albumDescriptionChange(portfolio, state).isDefined
                     case None        => portfolioDescriptionOf(portfolio).isDefined
                   }
                   val orderPending     = saved.exists(state => desiredAlbumOrder(portfolio, state) != state.albumOrder)
                   for {
                     driftCount <- (album, saved) match {
                                     case (Some(album), Some(state)) if state.uploaded.nonEmpty =>
                                       client
                                         .albumMediaItemIds(album.id)
                                         .map(serverIds => state.uploaded.values.count(id => !serverIds.contains(id)))
                                         .catchAll(_ => ZIO.succeed(0))
                                     case _                                                     => ZIO.succeed(0)
                                   }
                     notes       = List(
                                     Option.when(cropChangedAssets.nonEmpty)(s"${cropChangedAssets.size} changed crops would be re-rendered and re-uploaded"),
                                     Option.when(descriptionCount > 0)(s"$descriptionCount media descriptions would be updated"),
                                     Option.when(albumDescPending)("the portfolio description would be added to the album"),
                                     Option.when(removedEntries.nonEmpty)(s"${removedEntries.size} items would be removed from the album with --prune"),
                                     Option.when(orderPending)("the album would be re-arranged to portfolio order (oldest first)"),
                                     Option.when(driftCount > 0)(s"$driftCount items disappeared from the album on the Google Photos side and would be re-attached or re-uploaded"),
                                     Option.when(unmanageableIds.nonEmpty)(s"${unmanageableIds.size} medias are out of the app control on Google Photos and are left untouched")
                                   ).flatten
                     noteSuffix  = if (notes.isEmpty) "" else notes.mkString(", ", ", ", "")
                     _          <- Console.printLine(s"    would upload ${missingAssets.size} medias$noteSuffix (dry-run, use --execute)")
                   } yield saved
               }
    } yield state
  }

  // -------------------------------------------------------------------------------------------------------------------
  val logic = ZIO.logSpan("Synchronize portfolios with Google Photos albums") {
    for {
      args           <- getArgs
      executeMode     = args.exists(a => a == "--execute" || a == "-f")
      pruneMode       = args.contains("--prune")
      refreshDescMode = args.contains("--refresh-descriptions")
      retryUnmanaged  = args.contains("--retry-unmanageable")
      portfolioFilter = args.collectFirst { case a if a.startsWith("--portfolio=") => a.stripPrefix("--portfolio=").toLowerCase }
      _              <- ZIO.logInfo(
                          "Synchronizing portfolios to Google Photos albums" +
                            portfolioFilter.fold("")(f => s""" (portfolio filter "$f")""") +
                            (if (executeMode) " — EXECUTE mode" else " — dry-run, no change")
                        )
      config         <- oauthConfig
      backend        <- HttpClientZioBackend.scoped()
      initialState   <- stateLoad
      refreshToken   <- initialState.refreshToken match {
                          case Some(token) => ZIO.succeed(token)
                          case None        =>
                            for {
                              tokens       <- oauthAuthorizeInteractively(backend, config)
                              refreshToken <- ZIO.fromOption(tokens.refresh_token).orElseFail(Exception("Google didn't return a refresh token, retry after revoking the app access on https://myaccount.google.com/permissions"))
                              _            <- stateSave(initialState.copy(refreshToken = Some(refreshToken)))
                              _            <- ZIO.logInfo("Google Photos authorization successful, refresh token saved")
                            } yield refreshToken
                        }
      tokenCache     <- Ref.make(Option.empty[(String, Long)])
      client          = GooglePhotosClient(backend, AccessTokenProvider(backend, config, refreshToken, tokenCache))
      stateRef       <- Ref.make(initialState.copy(refreshToken = Some(refreshToken)))
      portfolios     <- MediaService
                          .portfolioList()
                          .runCollect
                          .map(_.toList.filter(p => portfolioFilter.forall(f => p.name.text.toLowerCase.contains(f))).sortBy(_.name.text))
      _              <- ZIO.logInfo(s"${portfolios.size} portfolios to synchronize")
      _              <- ZIO.foreachDiscard(portfolios) { portfolio =>
                          val saveState = (portfolioState: PortfolioSyncState) =>
                            stateRef
                              .updateAndGet(state => state.copy(portfolios = state.portfolios + (portfolio.id.asString -> portfolioState)))
                              .flatMap(stateSave)
                          stateRef.get.flatMap(state => synchronizePortfolio(client, portfolio, state.portfolios.get(portfolio.id.asString), executeMode, pruneMode, refreshDescMode, retryUnmanaged, saveState))
                        }
      finalState     <- stateRef.get
      totalUploaded   = finalState.portfolios.values.map(_.uploaded.size).sum
      _              <- if (executeMode) ZIO.logInfo(s"Synchronization done : $totalUploaded medias mirrored across ${finalState.portfolios.size} albums (state in $stateFilePath)")
                        else ZIO.logInfo("Dry-run done, rerun with --execute to apply")
    } yield ()
  }
}
