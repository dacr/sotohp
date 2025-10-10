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

  private def searchPredicate(
    includeMask: Option[IncludeMask],
    ignoreMask: Option[IgnoreMask],
    searchConfig: FileSystemSearchCoreConfig
  )(path: Path, attrs: BasicFileAttributes): Boolean = {
    // TAKE CARE WITH THIS PART OF THE CODE: TO AVOID ANY SECURITY ISSUE

    val basicChecks =
      ( // keep the parenthesis to enforce all conditions are taken into account with the && or || when spilling conditions across several lines
        attrs.isRegularFile &&
          (ignoreMask.isEmpty || ignoreMask.exists(_.toString.isEmpty) || !ignoreMask.get.isIgnored(path.toString)) &&
          (includeMask.isEmpty || includeMask.get.isIncluded(path.toString))
      )

    val securityConstraints =
      ( // keep the parenthesis to enforce all conditions are taken into account with the && or || when spilling conditions across several lines
        searchConfig.lockDirectory.isEmpty ||
          path.normalize().startsWith(searchConfig.lockDirectory.get.normalize()) // the normalize() operations are very important here
      )

    (basicChecks && securityConstraints)
  }

  private type SearchRootResult = (searchRoot: Store, originalPath: OriginalPath)

  private def fileStreamFromSearchRoot(
    searchRoot: Store,
    searchConfig: FileSystemSearchCoreConfig
  ): Either[FileSystemSearchIssue, JStream[SearchRootResult]] = {
    import searchRoot.{baseDirectory, includeMask, ignoreMask}

    val maxDepth = searchConfig.maxDepth

    val visitOptions: List[FileVisitOption] = if (searchConfig.followLinks) List(FileVisitOption.FOLLOW_LINKS) else Nil

    val triedFind = Try(Files.find(baseDirectory.path, maxDepth, searchPredicate(includeMask, ignoreMask, searchConfig), visitOptions*)) match {
      case Failure(exception) => Left(FileSystemSearchFindIssue(s"Couldn't create find file stream", exception))
      case Success(value)     => Right(value)
    }

    triedFind.map(stream =>
      stream
        .map(path => (searchRoot = searchRoot, originalPath = OriginalPath(path)))
    )
  }

  def originalsStreamFromSearchRoot(
    searchRoot: Store,
    searchConfig: FileSystemSearchCoreConfig
  ): Either[FileSystemSearchIssue, JStream[Either[OriginalIssue, Original]]] = {
    fileStreamFromSearchRoot(searchRoot, searchConfig)
      .map(stream =>
        stream.map { case (searchRoot = sr, originalPath = op) =>
          OriginalBuilder.originalFromFile(sr, op)
        }
      )
  }

  def mediasStreamFromSearchRoot(
                                  searchRoot: Store,
                                  searchConfig: FileSystemSearchCoreConfig,
                                  eventGetter: Original => Option[Event]
  ): Either[FileSystemSearchIssue, JStream[Either[CoreIssue, Media]]] = {
    originalsStreamFromSearchRoot(searchRoot, searchConfig)
      .map { stream =>
        stream.map { originalEither =>
          originalEither.flatMap { original =>
            MediaBuilder.mediaFromOriginal(original, eventGetter(original))
          }
        }
      }
  }

}
