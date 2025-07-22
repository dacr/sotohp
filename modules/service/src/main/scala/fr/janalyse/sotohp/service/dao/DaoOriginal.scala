package fr.janalyse.sotohp.service.dao

import fr.janalyse.sotohp.media.model.*
import zio.json.JsonCodec
import zio.lmdb.json.LMDBCodecJson

case class DaoOriginal(
  id: OriginalId,
  storeId: StoreId,
  mediaPath: OriginalPath,
  fileHash: FileHash,
  fileSize: FileSize,
  fileLastModified: FileLastModified,
  cameraShootDateTime: Option[ShootDateTime],
  cameraName: Option[CameraName],
  artistInfo: Option[ArtistInfo],
  dimension: Option[DaoDimension],
  orientation: Option[Orientation],
  location: Option[DaoLocation],
  aperture: Option[Aperture],
  exposureTime: Option[ExposureTime],
  iso: Option[ISO],
  focalLength: Option[FocalLength]
) derives LMDBCodecJson
