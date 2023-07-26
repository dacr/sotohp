package fr.janalyse.sotohp.core

import zio.*
import zio.ZIO.*
import zio.test.*

import fr.janalyse.sotohp.model.*
import PhotoOriginalsStream.*
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*
import java.util.UUID

object PhotoOriginalsStreamSpec extends ZIOSpecDefault {
  val searchPath1       = "samples/dataset1"
  val searchRoots1      = List(searchPath1)
  val photoFileExample1 = Path.of("samples/dataset1/example1.jpg")
  val photoFileExample2 = Path.of("samples/dataset1/example2.jpg")
  val photoOwnerId      = PhotoOwnerId(UUID.fromString("CAFECAFE-CAFE-CAFE-BABE-BABEBABE"))

  override def spec =
    suite("Photo original stream")(
      test("generate photo record") {
        for {
          photo         <- makePhoto(photoOwnerId, Path.of(searchPath1), photoFileExample1)
          photoMetaData <- from(photo.metaData)
          cameraName    <- from(photoMetaData.cameraName)
        } yield assertTrue(
          photo.source match {
            case file: PhotoSource.PhotoFile =>
              file.hash.code == "08dcaea985eaa1a9445bacc9dfe0f789092f9acfdc46d28e41cd0497444a9eae"
          },
          cameraName.contains("Canon")
        )
      },
      test("collect original photos") {
        val originals = photoOriginalStream(photoOwnerId, searchRoots1)
        for {
          originals <- originals.runCollect
        } yield assertTrue(
          originals.size == 2
        )
      }
    )
}
