package fr.janalyse.sotohp.core

import zio.*
import zio.ZIO.*
import zio.stream.*

import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{Files, Path}

import fr.janalyse.sotohp.model.*

import PhotoOperations.*

import fr.janalyse.sotohp.store.{PhotoStoreIssue, PhotoStoreService}

case class StreamIOIssue(message: String, exception: Throwable)

object OriginalsStream {

  private def searchPredicate(includeMaskRegex: Option[IncludeMaskRegex], ignoreMaskRegex: Option[IgnoreMaskRegex])(path: Path, attrs: BasicFileAttributes): Boolean = {
    attrs.isRegularFile &&
    (ignoreMaskRegex.isEmpty || ignoreMaskRegex.get.findFirstIn(path.toString).isEmpty) &&
    (includeMaskRegex.isEmpty || includeMaskRegex.get.findFirstIn(path.toString).isDefined)
  }

  private def findFromSearchRoot(
    searchRoot: PhotoSearchRoot
  ): ZStream[Any, StreamIOIssue, (PhotoSearchRoot, PhotoPath)] = {
    import searchRoot.{baseDirectory, includeMask, ignoreMask}

    val result = for {
      foundRelativeFilesJavaStream <- ZIO
                                        .attempt(Files.find(baseDirectory, 10, searchPredicate(includeMask, ignoreMask)))
                                        .mapError(err => StreamIOIssue("Couldn't create file stream", err))
      foundFileStream               = ZStream
                                        .fromJavaStream(foundRelativeFilesJavaStream)
                                        .mapBoth(err => StreamIOIssue("Couldn't convert file stream", err), photoPath => searchRoot -> photoPath)
    } yield foundFileStream
    ZStream.unwrap(result)
  }

  private def photoPathStream(searchRoots: List[PhotoSearchRoot]): ZStream[Any, StreamIOIssue, (PhotoSearchRoot, PhotoPath)] = {
    val foundFilesStreams = Chunk.fromIterable(searchRoots).map(searchRoot => findFromSearchRoot(searchRoot))
    val foundFilesStream  = ZStream.concatAll(foundFilesStreams)
    foundFilesStream
  }

  type PhotoStreamIssues = StreamIOIssue | PhotoFileIssue | PhotoStoreIssue | NotFoundInStore
  type PhotoStream       = ZStream[PhotoStoreService, PhotoStreamIssues, Photo]

  def photoStream(searchRoots: List[PhotoSearchRoot]): PhotoStream = {
    photoPathStream(searchRoots)
      .mapZIO((searchRoot, photoPath) => makePhoto(searchRoot.baseDirectory, photoPath, searchRoot.photoOwnerId))
  }

}
