package fr.janalyse.sotohp.cli

import fr.janalyse.sotohp.model.{PhotoOwnerId, PhotoSearchRoot}
import fr.janalyse.sotohp.core.OriginalsStream
import fr.janalyse.sotohp.store.PhotoStoreService
import zio.*
import zio.ZIO.*
import zio.lmdb.LMDB

import java.util.UUID

object Synchronize extends ZIOAppDefault {
  val synchronizeLogic = for {
    _                  <- logInfo("photos synchronization")
    ownerId            <- System
                            .env("PHOTOS_OWNER_ID")
                            .someOrFail("No owner identifier (PHOTOS_OWNER_ID environment variable)")
                            .map(id => PhotoOwnerId(UUID.fromString(id)))
    baseDirectories    <- System
                            .env("PHOTOS_SEARCH_ROOTS")
                            .someOrFail("Nowhere to search for photos (PHOTOS_SEARCH_ROOTS environment variable)")
                            .map(_.split("[,;]").toList.map(_.trim))
    includeMaskPattern <- System
                            .env("PHOTOS_SEARCH_INCLUDE_MASK")
                            .map(_.getOrElse("(?i)[.](?:(jpg)|(png)|(jpeg)|(tif)|(heic)|(gif)|(bmp))"))
                            .option
    ignoreMaskPattern  <- System.env("PHOTOS_SEARCH_IGNORE_MASK")
    searchRoots        <- foreach(baseDirectories)(baseDirectory => from(PhotoSearchRoot.build(ownerId, baseDirectory, includeMaskPattern, ignoreMaskPattern)))
    originals           = OriginalsStream.photoStream(searchRoots)
    count              <- originals.runCount
    _                  <- logInfo(s"Found $count photos")
    // photoFiles         <- originals.map(_.source.photoPath.toString).runCollect
    // _                  <- foreach(photoFiles.sorted)(f => Console.printLine(f))
  } yield ()

  override def run =
    synchronizeLogic
      .provide(
        LMDB.liveWithDatabaseName("photos"),
        PhotoStoreService.live,
        Scope.default
      )
}
