package fr.janalyse.sotohp.core

import zio.*
import zio.ZIO.*
import zio.test.*

import fr.janalyse.sotohp.model.*
import OriginalsStream.*
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*
import java.util.UUID

object OriginalsStreamSpec extends ZIOSpecDefault with TestDatasets {

  override def spec =
    suite("Photo original stream")(
      test("collect original photos with flat dataset") {
        for {
          photoSearchFileRoot <- from(PhotoSearchRoot.build(photoOwnerId, dataset1.toString))
          originalsStream      = photoStream(List(photoSearchFileRoot))
          photos              <- originalsStream.runCollect
          photoFileSources     = photos.map(_.source)
          photoFilePaths       = photoFileSources.map(_.photoPath)
        } yield assertTrue(
          photos.size == 5,
          photoFileSources.head.baseDirectory == dataset1,
          photoFilePaths.toSet == Set(dataset1Example1, dataset1Example2, dataset1Example3, dataset1Example4, dataset1Example5),
          photos.forall(_.category.isEmpty)
        )
      },
      test("collect original photos with tree dataset") {
        for {
          photoSearchFileRoot <- from(PhotoSearchRoot.build(photoOwnerId, dataset2.toString))
          originalsStream      = photoStream(List(photoSearchFileRoot))
          photos              <- originalsStream.runCollect
          photoFileSources     = photos.map(_.source)
          photoFilePaths       = photoFileSources.map(_.photoPath)
          photoCategories      = photos.flatMap(_.category.map(_.text))
        } yield assertTrue(
          photos.size == 2,
          photoFileSources.head.baseDirectory == dataset2,
          photoFilePaths.toSet == Set(dataset2tag1, dataset2landscape1),
          photoCategories.toSet == Set("landscapes", "tags")
        )
      }
    )
}