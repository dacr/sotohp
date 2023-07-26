package fr.janalyse.sotohp.core

import fr.janalyse.sotohp.core.PhotoOperations.*
import zio.*
import zio.ZIO.*
import zio.test.*

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

object PhotoOperationsSpec extends ZIOSpecDefault {
  val searchRoots1      = List("samples/dataset1")
  val photoFileExample1 = Path.of("samples", "dataset1", "example1.jpg")
  val photoFileExample2 = Path.of("samples", "dataset1", "example2.jpg")

  override def spec =
    suite("Photo operations")(
      test("read photo meta data")(
        for {
          metadata <- readMetadata(photoFileExample1)
        } yield assertTrue(
          metadata.getDirectories.asScala.size > 0
        )
      ),
      test("shootDateTime can be extracted")(
        for {
          metadata      <- readMetadata(photoFileExample1)
          shootDateTime <- from(extractShootDateTime(metadata))
        } yield assertTrue(
          shootDateTime.toString == "2023-07-26T10:05:59Z"
        )
      ),
      test("camera name can be extracted")(
        for {
          metadata      <- readMetadata(photoFileExample1)
          shootDateTime <- from(extractCameraName(metadata))
        } yield assertTrue(
          shootDateTime.contains("Canon")
        )
      ),
      test("geographic location can be extracted")(
        for {
          metadata1 <- readMetadata(photoFileExample1)
          metadata2 <- readMetadata(photoFileExample2)
          geoloc1   <- from(extractGeoPoint(metadata1))
          geoloc2   <- from(extractGeoPoint(metadata2))
        } yield assertTrue(
          geoloc1.latitude.doubleValue == 48.264875d,
          geoloc1.longitude.doubleValue == -1.666195d,
          geoloc1.altitude == 53.8d,
          geoloc2.latitude.doubleValue == 45.3453827d,
          geoloc2.longitude.doubleValue == 6.617216499722223d,
          geoloc2.altitude == 2081.0d
        )
      ),
      test("dimension can be extracted")(
        for {
          metadata  <- readMetadata(photoFileExample1)
          dimension <- from(extractDimension(metadata))
        } yield assertTrue(
          dimension.width == 1333,
          dimension.height == 2000
        )
      ),
      test("orientation can be extracted")(
        for {
          metadata    <- readMetadata(photoFileExample1)
          orientation <- from(extractOrientation(metadata))
        } yield assertTrue(
          orientation.code == 1,
          orientation.description == "Horizontal (normal)"
        )
      )
    )
}
