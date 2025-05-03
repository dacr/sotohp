package fr.janalyse.sotohp.store.dao

import java.nio.file.Path
import java.time.OffsetDateTime
import java.util.UUID
import zio.json.*
import zio.lmdb.json.LMDBCodecJson

case class DaoPhotoSource(
  photoId: String,
  originalOwnerId: String,
  originalBaseDirectory: String,
  originalPath: String,
  fileSize: Long,
  fileHash: String,
  fileLastModified: OffsetDateTime
) derives LMDBCodecJson
