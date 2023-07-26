package fr.janalyse.sotohp.core

import zio.*
import zio.ZIO.*
import zio.test.*

import fr.janalyse.sotohp.model.*
import PhotoOriginalsStream.*
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

object PhotoOriginalsStreamSpec extends ZIOSpecDefault {
  val searchPath1       = "samples/dataset1"
  val searchRoots1      = List(searchPath1)
  val photoFileExample1 = Path.of("samples", "dataset1", "example1.jpg")
  val photoFileExample2 = Path.of("samples", "dataset1", "example2.jpg")

  override def spec =
    suite("Photo original stream")(
      test("generate photo record") {
        for {
          photo1 <- makePhoto(Path.of(searchPath1), photoFileExample1)
        } yield assertTrue(
          photo1.source match {
            case file:PhotoSource.PhotoFile =>
              file.hash.code == "08dcaea985eaa1a9445bacc9dfe0f789092f9acfdc46d28e41cd0497444a9eae"
          }
        )
      },
      test("collect original photos") {
        val originals = photoOriginalStream(searchRoots1)
        for {
          originals <- originals.runCollect
        } yield assertTrue(
          originals.size == 2
        )
      }
    )
}
