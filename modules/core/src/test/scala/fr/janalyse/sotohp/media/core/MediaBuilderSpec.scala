package fr.janalyse.sotohp.media.core

import fr.janalyse.sotohp.media.core.OriginalBuilder.originalFromFile
import fr.janalyse.sotohp.media.core.MediaBuilder.*
import fr.janalyse.sotohp.media.model.*
import zio.*
import zio.ZIO.*
import zio.test.*

import java.nio.file.{Files, Path, Paths}
import java.time.{Instant, OffsetDateTime}
import scala.jdk.CollectionConverters.*

object MediaBuilderSpec extends ZIOSpecDefault with TestDatasets {
  override def spec = testLogic

  val testLogic =
    suite("Media builder")(
      test("Media event exists") {
        val check = (basedir: String, path: String, expected: Option[String]) => buildMediaEvent(BaseDirectoryPath(Path.of(basedir)), OriginalPath(Path.of(path))).map(_.name) == expected
        assertTrue(
          check("tmp", "tmp/toto.jpeg", None),
          check("tmp/", "tmp/toto.jpeg", None),
          check("tmp", "tmp/landscape/toto.jpeg", Some("landscape")),
          check("tmp/", "tmp/landscape/toto.jpeg", Some("landscape")),
          check("tmp", "tmp/country/landscape/toto.jpeg", Some("country/landscape"))
        )
      },
      test("generate media record") {
        for {
          original      <- from(originalFromFile(dataset1, dataset1Example1, fakeOwner.id, None))
          media         <- from(mediaFromOriginal(original, None))
        } yield assertTrue(
          media.original == original,
          media.kind == MediaKind.Photo
        )
      }
    )
}
