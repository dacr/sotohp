package fr.janalyse.sotohp.model

import java.nio.file.Path
import java.time.OffsetDateTime
import java.util.UUID

case class PhotoOwnerId(
  uuid: UUID
) extends AnyVal

type BaseDirectoryPath = Path
type PhotoPath         = Path
type FileSize          = Long
type FileLastModified  = OffsetDateTime

case class PhotoSource(
  ownerId: PhotoOwnerId,
  baseDirectory: BaseDirectoryPath,
  photoPath: PhotoPath,
  fileSize: FileSize,
  fileHash: PhotoHash,
  fileLastModified: FileLastModified
)
