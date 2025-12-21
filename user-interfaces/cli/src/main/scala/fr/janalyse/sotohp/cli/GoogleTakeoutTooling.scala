package fr.janalyse.sotohp.cli

import fr.janalyse.sotohp.cli.FaceInference.logic
import fr.janalyse.sotohp.core.*
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.processor.NormalizeProcessor
import fr.janalyse.sotohp.search.SearchService
import fr.janalyse.sotohp.service.MediaService
import wvlet.airframe.ulid.ULID
import zio.*
import zio.config.typesafe.*
import zio.lmdb.LMDB

import java.nio.file.Path
import java.time.temporal.ChronoUnit.{MONTHS, YEARS}
import java.time.{Instant, OffsetDateTime}
import java.util.UUID
import scala.io.AnsiColor.*
import zio.ZIO.*
import fr.janalyse.sotohp.core.OriginalBuilder
import zio.stream.ZStream

import scala.util.{Either, Left, Right}

object GoogleTakeoutTooling extends CommonsCLI {

  val fakeStoreId: StoreId = StoreId(UUID.fromString("aeaf65e3-55ba-49c9-9f72-ea016d8f9f9d"))

  val fakeOwner: Owner = Owner(
    id = OwnerId(ULID("01H84VVZRXZDZ5184V44KVWS3J")),
    firstName = FirstName("John"),
    lastName = LastName("Doe"),
    birthDate = Some(BirthDate(OffsetDateTime.parse("1970-01-01T00:00:00Z"))),
    None
  )
  val includeMask      = Option(
    IncludeMask("(?i)[.](?:(jpg)|(png)|(jpeg)|(tif)|(heic)|(gif)|(bmp))".r)
  )

  override def run =
    logic
      .provide(
        LMDB.live,
        SearchService.live,
        MediaService.live,
        Scope.default
      )

  def makeUniqueKey(original: Original): String = {
    // -- compute a kind of "unique" signature for each photo based on available meta data
    List(
      // in my case ~120000 photos for 1 owner, cameraShootDateTime is an acceptable criteria (when available)
      original.cameraShootDateTime.map(_.offsetDateTime.toInstant.toString).orElse(HashOperations.fileDigest(original.absoluteMediaPath).toOption),
      original.cameraName,
      original.artistInfo,
      original.exposureTime,
      original.aperture,
      original.focalLength,
      original.iso
      // original.mediaPath.extension.toLowerCase,
      // original.location,
      // original.orientation,
      // original.fileSize.value,    // not suitable as exported have very often lower size & quality to save space under google photo takeouts !!
      // original.mediaPath.fileName // name is not preserved, some prefix may be added by google photo takeouts
    ).map(_.toString).mkString("/")
  }

  val logic = ZIO.logSpan("google-takeout-check") {
    val baseDir = Path.of("/data/ALBUMS/google-photos-takeout/Takeout/TODO-synchronize")
    val store   = Store(fakeStoreId, None, fakeOwner.id, BaseDirectoryPath(baseDir), includeMask = includeMask)

    for {
      owner          <- MediaService.ownerList().runHead
      originals      <- MediaService
                          .originalList()
                          .runCollect
      existings       = originals.groupBy(makeUniqueKey)
      searchConfig    = FileSystemSearchCoreConfig()
      takeoutsStream <- ZIO
                          .from(FileSystemSearch.originalsStreamFromSearchRoot(store, searchConfig))
                          .map(ZStream.from)
      takeouts       <- takeoutsStream
                          .collect { case Right(takeout) => takeout }
                          .filter(_.mediaPath.path.toString.contains("Photos from "))
                          .runCollect
      allrights       = takeouts
                          .filter(takeout => existings.contains(makeUniqueKey(takeout)))
                          .sortBy(_.cameraShootDateTime.map(_.offsetDateTime))
      missings        = takeouts
                          .filterNot(takeout => existings.contains(makeUniqueKey(takeout)))
                          .sortBy(_.cameraShootDateTime.map(_.offsetDateTime))
      //_              <- ZIO.foreachDiscard(allrights)(notNeeded => ZIO.attempt(notNeeded.mediaPath.path.toFile.delete()))
      _              <- ZIO.foreachDiscard(missings)(missing => Console.printLine(s"${missing.mediaPath.fileName} - ${missing.timestamp}"))
      _              <- Console.printLine(s"${existings.size} photos already imported")
      _              <- Console.printLine(s"Found ${missings.size}/${takeouts.size} not locally synchronized photos")
    } yield ()
  }

}
