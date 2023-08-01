package fr.janalyse.sotohp.store.dao

import java.nio.file.Path
import java.time.OffsetDateTime
import java.util.UUID
import zio.json.*

case class DaoPhotoSource(
  ownerId: UUID,
  baseDirectory: String,
  photoPath: String,
  fileSize: Long,
  fileHash: String,
  fileLastModified: OffsetDateTime
) derives JsonCodec
