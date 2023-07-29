package fr.janalyse.sotohp.model

import java.nio.file.Path
import java.time.OffsetDateTime

type BaseDirectoryPath = Path
type PhotoPath         = Path
type FileSize          = Long
type FileLastModified  = OffsetDateTime

case class PhotoSource(
  baseDirectory: BaseDirectoryPath,
  photoPath: PhotoPath,
  size: FileSize,
  hash: Option[PhotoHash],
  lastModified: FileLastModified
)
