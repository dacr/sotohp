package fr.janalyse.sotohp.model

import java.nio.file.Path
import java.util.UUID
import scala.util.{Try, Success, Failure}
import scala.util.matching.Regex

type IncludeMaskRegex = Regex
type IgnoreMaskRegex  = Regex

case class PhotoSearchRoot private (
  id: UUID,
  photoOwnerId: PhotoOwnerId,
  baseDirectory: BaseDirectoryPath,
  includeMask: Option[IncludeMaskRegex],
  ignoreMask: Option[IgnoreMaskRegex]
)

object PhotoSearchRoot {
  def build(
    photoOwnerId: PhotoOwnerId,
    baseDirectorySpec: String,
    includeMaskPattern: Option[String] = None,
    ignoreMaskPattern: Option[String] = None
  ): Try[PhotoSearchRoot] =
    for {
      baseDirectory <- Try(Path.of(baseDirectorySpec).normalize())
                         .transform(
                           p => Success(p),
                           err => Failure(IllegalArgumentException(s"Given base directory $baseDirectorySpec is invalid", err))
                         )
      includeMask   <- Try(includeMaskPattern.map(_.r))
                         .transform(
                           p => Success(p),
                           err => Failure(IllegalArgumentException("Given include mask regex pattern is invalid", err))
                         )
      ignoreMask    <- Try(ignoreMaskPattern.map(_.r))
                         .transform(
                           p => Success(p),
                           err => Failure(IllegalArgumentException("Given ignore mask regex pattern is invalid", err))
                         )
      id             = UUID.randomUUID()
    } yield PhotoSearchRoot(
      id = id,
      photoOwnerId = photoOwnerId,
      baseDirectory = baseDirectory,
      includeMask = includeMask,
      ignoreMask = ignoreMask
    )
}
