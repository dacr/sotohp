package fr.janalyse.sotohp.media.core

import fr.janalyse.sotohp.media.core.MediaOperations.*
import fr.janalyse.sotohp.media.model.*
import zio.*
import zio.ZIO.*
import zio.test.*

import java.nio.file.{Files, Path, Paths}
import java.time.{Instant, OffsetDateTime}
import scala.jdk.CollectionConverters.*

object MediaOperationsSpec extends ZIOSpecDefault with TestDatasets {
  override def spec = testLogic

  val testLogic =
    suite("Photo operations")(
      test("photo event exists") {
        val check = (basedir: String, path: String, expected: Option[String]) => buildMediaEvent(BaseDirectoryPath(Path.of(basedir)), OriginalPath(Path.of(path))).map(_.name) == expected
        assertTrue(
          check("tmp", "tmp/toto.jpeg", None),
          check("tmp/", "tmp/toto.jpeg", None),
          check("tmp", "tmp/landscape/toto.jpeg", Some("landscape")),
          check("tmp/", "tmp/landscape/toto.jpeg", Some("landscape")),
          check("tmp", "tmp/country/landscape/toto.jpeg", Some("country/landscape"))
        )
      },
      test("read photo meta data")(
        for {
          metadata <- readDrewMetadata(dataset1Example1)
        } yield assertTrue(
          metadata.getDirectories.asScala.nonEmpty
        )
      ),
      test("exif date time can be parsed")(
        assertTrue(
          parseExifDateTimeFormat("2024:05:11 19:43:29", "+09:00").toInstant == Instant.parse("2024-05-11T10:43:29Z")
        )
      ),
      test("shootDateTime can be extracted")(
        for {
          metadata1      <- from(readDrewMetadata(dataset1Example1))
          shootDateTime1 <- from(extractShootDateTime(metadata1))
        } yield assertTrue(
          shootDateTime1.toString == "2023-07-26T10:05:59Z"
        )
      ),
      test("camera name can be extracted")(
        for {
          metadata      <- from(readDrewMetadata(dataset1Example1))
          shootDateTime <- from(extractCameraName(metadata))
        } yield assertTrue(
          shootDateTime.text.contains("Canon")
        )
      ),
      test("geographic location can be extracted")(
        for {
          metadata1 <- from(readDrewMetadata(dataset1Example1))
          metadata2 <- from(readDrewMetadata(dataset1Example2))
          geoloc1   <- from(extractLocation(metadata1))
          geoloc2   <- from(extractLocation(metadata2))
        } yield assertTrue(
          geoloc1.latitude.doubleValue == 48.264875d,
          geoloc1.longitude.doubleValue == -1.666195d,
          geoloc1.altitude.contains(53.8d),
          geoloc2.latitude.doubleValue == 45.3453827d,
          geoloc2.longitude.doubleValue == 6.617216499722223d,
          geoloc2.altitude.contains(2081.0d)
        )
      ),
      test("dimension can be extracted")(
        for {
          metadata  <- from(readDrewMetadata(dataset1Example1))
          dimension <- from(extractDimension(metadata))
        } yield assertTrue(
          dimension.width == 1333,
          dimension.height == 2000
        )
      ),
      test("orientation can be extracted")(
        for {
          metadata    <- from(readDrewMetadata(dataset1Example1))
          orientation <- from(extractOrientation(metadata))
        } yield assertTrue(
          orientation.code == 1,
          orientation.description == "Horizontal (normal)"
        )
      ),
      test("generate original record") {
        for {
          original   <- from(originalFromFile(dataset1, dataset1Example1, fakeOwner))
          cameraName <- from(original.cameraName)
        } yield assertTrue(
          original.fileHash.code == "08dcaea985eaa1a9445bacc9dfe0f789092f9acfdc46d28e41cd0497444a9eae",
          original.fileSize == FileSize(472624L),
          original.mediaPath == dataset1Example1,
          cameraName.text.contains("Canon")
        )
      },
      test("generate media record") {
        for {
          original      <- from(originalFromFile(dataset1, dataset1Example1, fakeOwner))
          media         <- from(mediaFromOriginal(original))
        } yield assertTrue(
          media.original == original,
          media.kind == MediaKind.Photo
        )
      }
    )
}
