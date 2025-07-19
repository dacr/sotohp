package fr.janalyse.sotohp.media.core

import fr.janalyse.sotohp.media.model.*
import zio.*
import zio.ZIO.*
import zio.stream.ZStream
import zio.test.*

import java.nio.file.{Files, Path, Paths}
import java.util.UUID
import scala.jdk.CollectionConverters.*

object FileSystemSearchSpec extends ZIOSpecDefault with TestDatasets {

  override def spec = testLogic

  val testLogic =
    suite("Photo original stream")(
      test("collect original photos with flat dataset") {
        for {
          photoSearchFileRoot <- from(FileSystemSearch.makeStore(fakeOwner.id, dataset1.toString))
          mediasJavaStream    <- from(FileSystemSearch.mediasStreamFromSearchRoot(photoSearchFileRoot, MediaBuilder.buildMediaEvent))
          mediasStream         = ZStream.fromJavaStream(mediasJavaStream)
          results             <- mediasStream.runCollect
          medias               = results.collect { case Right(original) => original }
          baseDirectories      = medias.map(_.original.baseDirectory)
          mediaPaths           = medias.map(_.original.mediaPath)
        } yield assertTrue(
          medias.size == 5,
          baseDirectories.head == dataset1,
          mediaPaths.toSet == Set(dataset1Example1, dataset1Example2, dataset1Example3, dataset1Example4, dataset1Example5),
          medias.forall(_.event.isEmpty)
        )
      },
      test("collect original photos with tree dataset") {
        for {
          photoSearchFileRoot <- from(FileSystemSearch.makeStore(fakeOwner.id, dataset2.toString))
          mediasJavaStream    <- from(FileSystemSearch.mediasStreamFromSearchRoot(photoSearchFileRoot, MediaBuilder.buildMediaEvent))
          mediasStream         = ZStream.fromJavaStream(mediasJavaStream)
          results             <- mediasStream.runCollect
          medias               = results.collect { case Right(original) => original }
          baseDirectories      = medias.map(_.original.baseDirectory)
          mediaPaths           = medias.map(_.original.mediaPath)
          mediaEvents          = medias.flatMap(_.event.map(_.name.text))
        } yield assertTrue(
          medias.size == 2,
          baseDirectories.head == dataset2,
          mediaPaths.toSet == Set(dataset2tag1, dataset2landscape1),
          mediaEvents.toSet == Set("landscapes", "tags")
        )
      }
    )
}
