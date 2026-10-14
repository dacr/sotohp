package fr.janalyse.sotohp.processor

import fr.janalyse.sotohp.core.LogContext
import fr.janalyse.sotohp.processor.config.GeocodingConfig
import zio.*
import zio.ZIOAspect.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path, StandardCopyOption}
import java.time.Duration as JDuration
import java.util.zip.ZipInputStream
import scala.util.Using

/** Fetches the GeoNames dump files [[GeoPlaceResolver]] needs, so that no manual step is required.
  *
  * Only missing files are downloaded - an existing file is never refreshed (delete it for that).
  * Each file is first written as `<name>.part` then atomically moved into place, so an interrupted
  * download never leaves a truncated file that would later pass for a complete one. A failure
  * (offline, 404, ...) is logged and skipped: the resolver then loads whatever is present.
  */
object GeoNamesDownloader {

  private val textFiles = List("admin1CodesASCII.txt", "admin2Codes.txt", "countryInfo.txt")

  private lazy val httpClient: HttpClient =
    HttpClient
      .newBuilder()
      .followRedirects(HttpClient.Redirect.NORMAL)
      .connectTimeout(JDuration.ofSeconds(30))
      .build()

  def ensureAvailable(config: GeocodingConfig): UIO[Unit] = {
    val dir     = Path.of(config.geonamesDirectory)
    val baseUrl = config.downloadBaseUrl.stripSuffix("/")
    // `cities500.txt` is published as `cities500.zip`, `FR.txt` as `FR.zip`
    val zipped  = (config.citiesFile :: GeoPlaceResolver.countryFileNames(config)).map(name => name -> s"$baseUrl/${name.stripSuffix(".txt")}.zip")
    val plain   = textFiles.map(name => name -> s"$baseUrl/$name")
    val missing = (zipped.map((name, url) => (name, url, true)) ++ plain.map((name, url) => (name, url, false)))
      .filterNot((name, _, _) => Files.exists(dir.resolve(name)))

    ZIO
      .foreachDiscard(missing) { (name, url, isZip) =>
        val target = dir.resolve(name)
        val logic  = for {
          _     <- ZIO.logInfo("Downloading GeoNames file")
          bytes <- ZIO.attemptBlocking(download(dir, target, url, isZip))
          _     <- ZIO.logInfo("GeoNames file downloaded") @@ LogContext.kv("bytes" -> bytes)
        } yield ()
        (logic @@ LogContext.kv("geonamesFile" -> target, "url" -> url))
          .catchAll(err => ZIO.logWarning(s"Unable to download GeoNames file : $err") @@ LogContext.kv("geonamesFile" -> target, "url" -> url))
      }
      .when(missing.nonEmpty)
      .unit
  }

  /** Downloads `url` to `target` (extracting the entry named like `target` when it is a zip). Returns the final file size. */
  private def download(dir: Path, target: Path, url: String, isZip: Boolean): Long = {
    Files.createDirectories(dir)
    val part       = target.resolveSibling(s"${target.getFileName}.part")
    val zipPart    = target.resolveSibling(s"${target.getFileName}.zip.part")
    val downloaded = if (isZip) zipPart else part
    try {
      val request  = HttpRequest.newBuilder(URI.create(url)).GET().build()
      val response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(downloaded))
      if (response.statusCode() != 200) throw new IllegalStateException(s"HTTP ${response.statusCode()}")
      if (isZip) extractEntry(zipPart, target.getFileName.toString, part)
      Files.move(part, target, StandardCopyOption.ATOMIC_MOVE)
      Files.size(target)
    } finally {
      Files.deleteIfExists(part)
      Files.deleteIfExists(zipPart)
    }
  }

  private def extractEntry(zip: Path, entryName: String, to: Path): Unit =
    Using.resource(new ZipInputStream(Files.newInputStream(zip))) { in =>
      val entry = Iterator.continually(in.getNextEntry).takeWhile(_ != null).find(_.getName == entryName)
      if (entry.isEmpty) throw new IllegalStateException(s"$entryName not found in the downloaded archive")
      Files.copy(in, to, StandardCopyOption.REPLACE_EXISTING)
    }
}
