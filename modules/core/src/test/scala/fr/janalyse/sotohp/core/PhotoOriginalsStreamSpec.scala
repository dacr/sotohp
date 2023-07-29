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
  val photoOwnerId = PhotoOwnerId(UUID.fromString("CAFECAFE-CAFE-CAFE-BABE-BABEBABE"))

  val dataset1         = "samples/dataset1"
  val dataset1Example1 = Path.of("samples/dataset1/example1.jpg")
  val dataset1Example2 = Path.of("samples/dataset1/example2.jpg")

  val dataset2           = "samples/dataset2"
  val dataset2tag1       = Path.of("samples/dataset2/tags/tag1.jpg")
  val dataset2landscape1 = Path.of("samples/dataset2/landscapes/landscape1.jpg")

  override def spec =
    suite("Photo original stream")(
      test("generate photo record") {
        for {
          photoSearchFileRoot <- from(PhotoSearchFileRoot.build(photoOwnerId, dataset1))
          photo               <- makePhoto(photoSearchFileRoot, dataset1Example1)
          photoMetaData       <- from(photo.metaData)
          cameraName          <- from(photoMetaData.cameraName)
        } yield assertTrue(
          // photo.source.hash.code == "08dcaea985eaa1a9445bacc9dfe0f789092f9acfdc46d28e41cd0497444a9eae"
          photo.source.size == 472624L && photo.source.photoPath == dataset1Example1,
          cameraName.contains("Canon")
        )
      },
      test("collect original photos with flat dataset") {
        for {
          photoSearchFileRoot <- from(PhotoSearchFileRoot.build(photoOwnerId, dataset1))
          originalsStream      = photoOriginalStream(List(photoSearchFileRoot))
          photos              <- originalsStream.runCollect
          photoFileSources     = photos.map(_.source)
          photoFilePaths       = photoFileSources.map(_.photoPath)
        } yield assertTrue(
          photos.size == 2,
          photoFileSources.head.baseDirectory == Path.of(dataset1),
          photoFilePaths.toSet == Set(dataset1Example1, dataset1Example2),
          photos.forall(_.category.isEmpty)
        )
      },
      test("collect original photos with tree dataset") {
        for {
          photoSearchFileRoot <- from(PhotoSearchFileRoot.build(photoOwnerId, dataset2))
          originalsStream      = photoOriginalStream(List(photoSearchFileRoot))
          photos              <- originalsStream.runCollect
          photoFileSources     = photos.map(_.source)
          photoFilePaths       = photoFileSources.map(_.photoPath)
          photoCategories      = photos.flatMap(_.category.map(_.text))
        } yield assertTrue(
          photos.size == 2,
          photoFileSources.head.baseDirectory == Path.of(dataset2),
          photoFilePaths.toSet == Set(dataset2tag1, dataset2landscape1),
          photoCategories.toSet == Set("landscapes", "tags")
        )
      }
    )
}
