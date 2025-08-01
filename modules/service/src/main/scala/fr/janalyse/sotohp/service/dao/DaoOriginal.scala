package fr.janalyse.sotohp.service.dao

import fr.janalyse.sotohp.model.*
import io.scalaland.chimney.Transformer
import zio.json.JsonCodec
import zio.lmdb.json.LMDBCodecJson

case class DaoOriginal(
  id: OriginalId,
  storeId: StoreId,
  mediaPath: OriginalPath,
  fileSize: FileSize,
  fileLastModified: FileLastModified,
  kind: MediaKind,
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

object DaoOriginal {
  given Transformer[Original, DaoOriginal] =
    Transformer
      .define[Original, DaoOriginal]
      .withFieldComputed(_.storeId, _.store.id)
      .buildTransformer
}
