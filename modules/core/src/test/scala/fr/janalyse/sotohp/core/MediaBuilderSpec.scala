package fr.janalyse.sotohp.core

import fr.janalyse.sotohp.core.OriginalBuilder.originalFromFile
import fr.janalyse.sotohp.core.MediaBuilder.*
import fr.janalyse.sotohp.model.*
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
        val fakeStore = Store(fakeStoreId1, None, fakeOwner.id, BaseDirectoryPath(Path.of("tmp")))
        val check     = (basedir: String, path: String, expected: Option[String]) => buildDefaultMediaEvent(fakeStore, OriginalPath(Path.of(path)), None).map(_.name) == expected
        assertTrue(
          check("tmp", "toto.jpeg", None),
          check("tmp/", "toto.jpeg", None),
          check("tmp", "landscape/toto.jpeg", Some("landscape")),
          check("tmp/", "landscape/toto.jpeg", Some("landscape")),
          check("tmp", "country/landscape/toto.jpeg", Some("country/landscape"))
        )
      },
      test("generate media record") {
        for {
          original <- from(originalFromFile(fakeStore1, dataset1Example1))
          media    <- from(mediaFromOriginal(original, None))
        } yield assertTrue(
          media.original == original
        )
      }
    )
}
