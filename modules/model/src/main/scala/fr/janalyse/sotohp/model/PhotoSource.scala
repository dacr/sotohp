package fr.janalyse.sotohp.model

import wvlet.airframe.ulid.ULID

import java.nio.file.Path
import java.time.OffsetDateTime
import java.util.UUID

case class PhotoOwnerId(
  id: UUID
) extends AnyVal {
  override def toString: String = id.toString
}

type BaseDirectoryPath = Path
type PhotoPath         = Path
type FileSize          = Long
type FileLastModified  = OffsetDateTime

case class OriginalId(
  id: UUID
) extends AnyVal {
  override def toString(): String = id.toString
}

case class Original(
  ownerId: PhotoOwnerId,
  baseDirectory: BaseDirectoryPath,
  path: PhotoPath
)

case class PhotoId(
  id: ULID
) extends AnyVal {
  override def toString(): String = id.toString
}

case class PhotoSource(
  photoId: PhotoId,
  original: Original,
  fileHash: PhotoHash,
  fileSize: FileSize,
  fileLastModified: FileLastModified
)
