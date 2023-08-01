package fr.janalyse.sotohp.core

import zio.*
import zio.ZIO.*
import zio.stream.*

import java.io.{File, IOException}
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{Files, Path, Paths}
import java.time.{Instant, OffsetDateTime, ZoneId}
import com.drew.metadata.Metadata
import fr.janalyse.sotohp.model.{PhotoMetaData, *}
import fr.janalyse.sotohp.model.DegreeMinuteSeconds.*
import fr.janalyse.sotohp.model.DecimalDegrees.*

import scala.jdk.CollectionConverters.*
import PhotoOperations.*
import fr.janalyse.sotohp.model.*
import java.util.UUID

import fr.janalyse.sotohp.store.PhotoStoreService

object OriginalsStream {

  private def searchPredicate(includeMaskRegex: Option[IncludeMaskRegex], ignoreMaskRegex: Option[IgnoreMaskRegex])(path: Path, attrs: BasicFileAttributes): Boolean = {
    attrs.isRegularFile &&
    (ignoreMaskRegex.isEmpty || ignoreMaskRegex.get.findFirstIn(path.toString).isEmpty) &&
    (includeMaskRegex.isEmpty || includeMaskRegex.get.findFirstIn(path.toString).isDefined)
  }

  private def findFromSearchRoot(
    searchRoot: PhotoSearchRoot
  ): ZStream[Any, Throwable, (PhotoSearchRoot, PhotoPath)] = {
    import searchRoot.{baseDirectory, includeMask, ignoreMask}
    val foundRelativeFilesJavaStream = Files.find(baseDirectory, 10, searchPredicate(includeMask, ignoreMask))
    val foundFileStream              = ZStream
      .fromJavaStream(foundRelativeFilesJavaStream)
      .map(photoPath => searchRoot -> photoPath)
    foundFileStream
  }

  private def photoPathStream(searchRoots: List[PhotoSearchRoot]): ZStream[Any, Throwable, (PhotoSearchRoot, PhotoPath)] = {
    val foundFilesStreams = Chunk.fromIterable(searchRoots).map(searchRoot => findFromSearchRoot(searchRoot))
    val foundFilesStream  = ZStream.concatAll(foundFilesStreams)
    foundFilesStream
  }

  def photoStream(searchRoots: List[PhotoSearchRoot]): ZStream[PhotoStoreService, Throwable, Photo] = {
    photoPathStream(searchRoots)
      .mapZIOParUnordered(4)((searchRoot, photoPath) => makePhoto(searchRoot.baseDirectory, photoPath, searchRoot.photoOwnerId))
      .tapError(err => logError(s"error on stream : ${err.getMessage}"))
  }

}
