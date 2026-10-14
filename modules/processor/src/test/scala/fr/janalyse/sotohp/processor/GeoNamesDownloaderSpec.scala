package fr.janalyse.sotohp.processor

import com.sun.net.httpserver.HttpServer
import fr.janalyse.sotohp.processor.config.GeocodingConfig
import zio.*
import zio.test.*

import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.{ZipEntry, ZipOutputStream}
import scala.jdk.CollectionConverters.*

/** Serves a fake GeoNames dump from a local HTTP server, in the layout of download.geonames.org. */
object GeoNamesDownloaderSpec extends BaseSpecDefault {

  private def zipOf(entries: (String, String)*): Array[Byte] = {
    val out = new ByteArrayOutputStream()
    val zip = new ZipOutputStream(out)
    entries.foreach { (name, content) =>
      zip.putNextEntry(new ZipEntry(name))
      zip.write(content.getBytes(StandardCharsets.UTF_8))
      zip.closeEntry()
    }
    zip.close()
    out.toByteArray
  }

  private val published: Map[String, Array[Byte]] = Map(
    "/cities500.zip"         -> zipOf("cities500.txt" -> "cities\n"),
    "/FR.zip"                -> zipOf("readme.txt" -> "readme\n", "FR.txt" -> "france\n"),
    "/admin1CodesASCII.txt"  -> "admin1\n".getBytes(StandardCharsets.UTF_8),
    "/admin2Codes.txt"       -> "admin2\n".getBytes(StandardCharsets.UTF_8),
    "/countryInfo.txt"       -> "countries\n".getBytes(StandardCharsets.UTF_8)
  )

  /** A server publishing `published`, counting the requests it answered. */
  private val server: ZIO[Scope, Throwable, (String, AtomicInteger)] =
    ZIO.acquireRelease(ZIO.attempt {
      val requests = new AtomicInteger(0)
      val http     = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
      http.createContext(
        "/",
        exchange => {
          requests.incrementAndGet()
          published.get(exchange.getRequestURI.getPath) match {
            case Some(body) =>
              exchange.sendResponseHeaders(200, body.length.toLong)
              exchange.getResponseBody.write(body)
            case None       =>
              exchange.sendResponseHeaders(404, -1)
          }
          exchange.close()
        }
      )
      http.start()
      (http, requests)
    })((http, _) => ZIO.succeed(http.stop(0))).map((http, requests) => (s"http://127.0.0.1:${http.getAddress.getPort}", requests))

  private val tempDir: ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(ZIO.attempt(Files.createTempDirectory("geonames-test")))(dir =>
      ZIO.attempt(Files.walk(dir).iterator().asScala.toList.reverse.foreach(Files.deleteIfExists)).orDie
    )

  private def configFor(dir: Path, baseUrl: String) =
    GeocodingConfig(
      geonamesDirectory = dir.resolve("geonames").toString, // not created yet: the downloader must create it
      citiesFile = "cities500.txt",
      countryFiles = "FR.txt",
      autoDownload = true,
      downloadBaseUrl = baseUrl,
      maxDistanceKm = 150
    )

  private def read(dir: Path, name: String) = Files.readString(dir.resolve("geonames").resolve(name))

  private def listing(dir: Path) = Files.list(dir.resolve("geonames")).iterator().asScala.map(_.getFileName.toString).toSet

  override def spec = suite("GeoNamesDownloader")(
    test("downloads every missing file, extracting the zipped ones") {
      for {
        dir                 <- tempDir
        (baseUrl, requests) <- server
        _                   <- GeoNamesDownloader.ensureAvailable(configFor(dir, baseUrl))
      } yield assertTrue(
        read(dir, "cities500.txt") == "cities\n",
        read(dir, "FR.txt") == "france\n",
        read(dir, "admin1CodesASCII.txt") == "admin1\n",
        read(dir, "admin2Codes.txt") == "admin2\n",
        read(dir, "countryInfo.txt") == "countries\n",
        listing(dir) == Set("cities500.txt", "FR.txt", "admin1CodesASCII.txt", "admin2Codes.txt", "countryInfo.txt"),
        requests.get == 5
      )
    },
    test("existing files are not downloaded again") {
      for {
        dir                 <- tempDir
        (baseUrl, requests) <- server
        _                   <- ZIO.attempt {
                                 Files.createDirectories(dir.resolve("geonames"))
                                 Files.writeString(dir.resolve("geonames").resolve("FR.txt"), "mine\n")
                               }
        _                   <- GeoNamesDownloader.ensureAvailable(configFor(dir, baseUrl))
        _                   <- GeoNamesDownloader.ensureAvailable(configFor(dir, baseUrl))
      } yield assertTrue(read(dir, "FR.txt") == "mine\n", requests.get == 4)
    },
    test("a failing download is skipped, without leftovers") {
      for {
        dir                 <- tempDir
        (baseUrl, _)        <- server
        _                   <- GeoNamesDownloader.ensureAvailable(configFor(dir, baseUrl).copy(countryFiles = "XX.txt"))
        _                   <- GeoNamesDownloader.ensureAvailable(configFor(dir, "http://127.0.0.1:1").copy(citiesFile = "cities1000.txt"))
      } yield assertTrue(
        !listing(dir).contains("XX.txt"),
        !listing(dir).contains("cities1000.txt"),
        !listing(dir).exists(_.endsWith(".part"))
      )
    }
  )
}
