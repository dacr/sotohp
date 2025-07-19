package fr.janalyse.sotohp.service.dao

import fr.janalyse.sotohp.media.model.*
import zio.json.JsonCodec
import zio.lmdb.json.LMDBCodecJson

case class DaoOriginal(
  id: OriginalId,
  baseDirectory: BaseDirectoryPath,
  mediaPath: OriginalPath,
  ownerId: OwnerId,
  fileHash: FileHash,
  fileSize: FileSize,
  fileLastModified: FileLastModified,
  cameraShootDateTime: Option[ShootDateTime],
  cameraName: Option[CameraName],
  dimension: Option[DaoDimension],
  orientation: Option[Orientation],
  location: Option[DaoLocation]
) derives LMDBCodecJson
