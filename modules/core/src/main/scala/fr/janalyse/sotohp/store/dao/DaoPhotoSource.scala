package fr.janalyse.sotohp.store.dao

import java.nio.file.Path
import java.time.OffsetDateTime
import java.util.UUID
import zio.json.*

case class DaoPhotoSource(
  photoId: String,
  originalOwnerId: UUID,
  originalBaseDirectory: String,
  originalPath: String,
  fileSize: Long,
  fileHash: String,
  fileLastModified: OffsetDateTime
) derives JsonCodec
