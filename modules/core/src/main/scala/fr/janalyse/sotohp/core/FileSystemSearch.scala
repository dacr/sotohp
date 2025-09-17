package fr.janalyse.sotohp.core

import fr.janalyse.sotohp.model.*

import java.nio.file.{FileVisitOption, Files, Path}
import java.nio.file.attribute.BasicFileAttributes
import java.util.{UUID, stream}
import scala.util.matching.Regex
import scala.util.{Failure, Success, Try}
import java.util.stream.{Stream => JStream}

object FileSystemSearch {

  private def makeBaseDirectory(baseDirectorySpec: String): Either[FileSystemSearchIssue, BaseDirectoryPath] = {
    Try(Path.of(baseDirectorySpec).normalize()) match {
      case Failure(exception) => Left(FileSystemSearchInvalidBaseDirectory(s"Given base directory is invalid", baseDirectorySpec, exception))
      case Success(value)     => Right(BaseDirectoryPath(value))
    }
  }

  private def makeIncludeMask(includeMaskPattern: Option[String]): Either[FileSystemSearchIssue, Option[IncludeMask]] = {
    Try(includeMaskPattern.map(_.r)) match {
      case Failure(exception) => Left(FileSystemSearchInvalidPatternFilter("Given include mask regex pattern is invalid", exception))
      case Success(value)     => Right(value.map(IncludeMask.apply))
    }
  }

  private def makeIgnoreMask(ignoreMaskPattern: Option[String]): Either[FileSystemSearchIssue, Option[IgnoreMask]] = {
    Try(ignoreMaskPattern.map(_.r)) match {
      case Failure(exception) => Left(FileSystemSearchInvalidPatternFilter("Given ignore mask regex pattern is invalid", exception))
      case Success(value)     => Right(value.map(IgnoreMask.apply))
    }
  }

  def makeStore(
    ownerId: OwnerId,
    name: Option[StoreName],
    baseDirectorySpec: String,
    includeMaskPattern: Option[String] = None,
    ignoreMaskPattern: Option[String] = None
  ): Either[FileSystemSearchIssue, Store] =
    for {
      baseDirectory <- makeBaseDirectory(baseDirectorySpec)
      includeMask   <- makeIncludeMask(includeMaskPattern)
      ignoreMask    <- makeIgnoreMask(ignoreMaskPattern)
      storeId        = StoreId(UUID.randomUUID())
    } yield Store(
      id = storeId,
      name = name,
      ownerId = ownerId,
      baseDirectory = baseDirectory,
      includeMask = includeMask,
      ignoreMask = ignoreMask
    )

  private def searchPredicate(includeMask: Option[IncludeMask], ignoreMask: Option[IgnoreMask])(path: Path, attrs: BasicFileAttributes): Boolean = {
    attrs.isRegularFile &&
    (ignoreMask.isEmpty || !ignoreMask.get.isIgnored(path.toString)) &&
    (includeMask.isEmpty || includeMask.get.isIncluded(path.toString))
  }

  private type SearchRootResult = (searchRoot: Store, originalPath: OriginalPath)

  private def fileStreamFromSearchRoot(
    searchRoot: Store
  ): Either[FileSystemSearchIssue, JStream[SearchRootResult]] = {
    import searchRoot.{baseDirectory, includeMask, ignoreMask}
    val maxDepth   = 10
    val javaStream = Try(Files.find(baseDirectory.path, maxDepth, searchPredicate(includeMask, ignoreMask), FileVisitOption.FOLLOW_LINKS)) match {
      case Failure(exception) => Left(FileSystemSearchFindIssue(s"Couldn't create find file stream", exception))
      case Success(value)     => Right(value)
    }

    javaStream.map(stream => stream.map(path => (searchRoot = searchRoot, originalPath = OriginalPath(path))))
  }

  def originalsStreamFromSearchRoot(
    searchRoot: Store
  ): Either[FileSystemSearchIssue, JStream[Either[OriginalIssue, Original]]] = {
    fileStreamFromSearchRoot(searchRoot)
      .map(stream =>
        stream.map { case (searchRoot = sr, originalPath = op) =>
          OriginalBuilder.originalFromFile(sr, op)
        }
      )
  }

  def mediasStreamFromSearchRoot(
    searchRoot: Store,
    eventGetter: Original => Option[Event]
  ): Either[FileSystemSearchIssue, JStream[Either[CoreIssue, Media]]] = {
    originalsStreamFromSearchRoot(searchRoot)
      .map { stream =>
        stream.map { originalEither =>
          originalEither.flatMap { original =>
            MediaBuilder.mediaFromOriginal(original, eventGetter(original))
          }
        }
      }
  }

}
