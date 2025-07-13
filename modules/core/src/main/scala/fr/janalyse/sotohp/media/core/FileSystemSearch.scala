package fr.janalyse.sotohp.media.core

import fr.janalyse.sotohp.media.model.*

import java.nio.file.{FileVisitOption, Files, Path}
import java.nio.file.attribute.BasicFileAttributes
import java.util.{UUID, stream}
import scala.util.matching.Regex
import scala.util.{Failure, Success, Try}
import java.util.stream.{Stream => JStream}

opaque type IncludeMask = Regex

object IncludeMask {
  def apply(regex: Regex): IncludeMask = regex
}
extension (includeMask: IncludeMask) {
  def isIncluded(path: String): Boolean = includeMask.findFirstIn(path).isDefined
}

opaque type IgnoreMask = Regex

object IgnoreMask {
  def apply(regex: Regex): IgnoreMask = regex
}
extension (ignoreMaskRegex: IgnoreMask) {
  def isIgnored(path: String): Boolean = ignoreMaskRegex.findFirstIn(path).isDefined
}

case class FileSystemSearchRoot private (
  owner: Owner,
  baseDirectory: BaseDirectoryPath,
  includeMask: Option[IncludeMask],
  ignoreMask: Option[IgnoreMask]
)

object FileSystemSearchRoot {

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

  def build(
    owner: Owner,
    baseDirectorySpec: String,
    includeMaskPattern: Option[String] = None,
    ignoreMaskPattern: Option[String] = None
  ): Either[FileSystemSearchIssue, FileSystemSearchRoot] =
    for {
      baseDirectory <- makeBaseDirectory(baseDirectorySpec)
      includeMask   <- makeIncludeMask(includeMaskPattern)
      ignoreMask    <- makeIgnoreMask(ignoreMaskPattern)
    } yield FileSystemSearchRoot(
      owner = owner,
      baseDirectory = baseDirectory,
      includeMask = includeMask,
      ignoreMask = ignoreMask
    )
}

object FileSystemSearch {
  private def searchPredicate(includeMask: Option[IncludeMask], ignoreMask: Option[IgnoreMask])(path: Path, attrs: BasicFileAttributes): Boolean = {
    attrs.isRegularFile &&
    (ignoreMask.isEmpty || !ignoreMask.get.isIgnored(path.toString)) &&
    (includeMask.isEmpty || includeMask.get.isIncluded(path.toString))
  }

  type SearchRootResult = (searchRoot: FileSystemSearchRoot, originalPath: OriginalPath)

  def fileStreamFromSearchRoot(
    searchRoot: FileSystemSearchRoot
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
    searchRoot: FileSystemSearchRoot
  ): Either[FileSystemSearchIssue, JStream[Either[OriginalIssue, Original]]] = {
    fileStreamFromSearchRoot(searchRoot)
      .map(stream =>
        stream.map { case (searchRoot = sr, originalPath = op) =>
          OriginalBuilder.originalFromFile(sr.baseDirectory, op, sr.owner)
        }
      )
  }

  def mediasStreamFromSearchRoot(
    searchRoot: FileSystemSearchRoot
  ): Either[FileSystemSearchIssue, JStream[Either[CoreIssue, Media]]] = {
    originalsStreamFromSearchRoot(searchRoot)
      .map(stream => stream.map(originalEither => originalEither.flatMap(original => MediaBuilder.mediaFromOriginal(original))))
  }

}
