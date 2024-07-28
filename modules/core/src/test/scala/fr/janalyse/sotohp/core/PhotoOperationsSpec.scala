package fr.janalyse.sotohp.core

import fr.janalyse.sotohp.core.PhotoOperations.*
import fr.janalyse.sotohp.model.*
import zio.*
import zio.ZIO.*
import zio.test.*

import java.nio.file.{Files, Path, Paths}
import java.time.{Instant, OffsetDateTime}
import scala.jdk.CollectionConverters.*

object PhotoOperationsSpec extends ZIOSpecDefault with TestDatasets {
  override def spec = testLogic.provideLayer(PhotoStoreServiceFake.default)

  val testLogic =
    suite("Photo operations")(
      test("photo event exists") {
        val check = (basedir: String, path: String, expected: Option[String]) => buildPhotoEvent(Path.of(basedir), Path.of(path)) == expected.map(PhotoEvent.apply)
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
          metadata.getDirectories.asScala.size > 0
        )
      ),
      test("exif date time can be parsed")(
        assertTrue(
          parseExifDateTimeFormat("2024:05:11 19:43:29", "+09:00").toInstant == Instant.parse("2024-05-11T10:43:29Z")
        )
      ),
      test("shootDateTime can be extracted")(
        for {
          metadata1      <- readDrewMetadata(dataset1Example1)
          shootDateTime1 <- from(extractShootDateTime(metadata1))
        } yield assertTrue(
          shootDateTime1.toString == "2023-07-26T10:05:59Z"
        )
      ),
      test("camera name can be extracted")(
        for {
          metadata      <- readDrewMetadata(dataset1Example1)
          shootDateTime <- from(extractCameraName(metadata))
        } yield assertTrue(
          shootDateTime.contains("Canon")
        )
      ),
      test("geographic location can be extracted")(
        for {
          metadata1 <- readDrewMetadata(dataset1Example1)
          metadata2 <- readDrewMetadata(dataset1Example2)
          geoloc1   <- from(extractPlace(metadata1))
          geoloc2   <- from(extractPlace(metadata2))
        } yield assertTrue(
          geoloc1.latitude.doubleValue == 48.264875d,
          geoloc1.longitude.doubleValue == -1.666195d,
          geoloc1.altitude == Some(53.8d),
          geoloc2.latitude.doubleValue == 45.3453827d,
          geoloc2.longitude.doubleValue == 6.617216499722223d,
          geoloc2.altitude == Some(2081.0d)
        )
      ),
      test("dimension can be extracted")(
        for {
          metadata  <- readDrewMetadata(dataset1Example1)
          dimension <- from(extractDimension(metadata))
        } yield assertTrue(
          dimension.width == 1333,
          dimension.height == 2000
        )
      ),
      test("orientation can be extracted")(
        for {
          metadata    <- readDrewMetadata(dataset1Example1)
          orientation <- from(extractOrientation(metadata))
        } yield assertTrue(
          orientation.code == 1,
          orientation.description == "Horizontal (normal)"
        )
      ),
      test("generate photo record") {
        for {
          photo         <- makePhoto(Original(photoOwnerId, dataset1, dataset1Example1))
          photoMetaData <- from(photo.metaData)
          cameraName    <- from(photoMetaData.cameraName)
        } yield assertTrue(
          // photo.source.hash.code == "08dcaea985eaa1a9445bacc9dfe0f789092f9acfdc46d28e41cd0497444a9eae"
          photo.source.fileSize == 472624L && photo.source.original.path == dataset1Example1,
          cameraName.contains("Canon")
        )
      }
    )
}
