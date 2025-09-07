package fr.janalyse.sotohp.api.protocol

import fr.janalyse.sotohp.model.{Aperture, ArtistInfo, CameraName, Dimension, ExposureTime, FileLastModified, FileSize, FocalLength, ISO, Location, MediaKind, Orientation, Original, OriginalId, OriginalPath, ShootDateTime, Store, StoreId}
import zio.json.JsonCodec
import fr.janalyse.sotohp.service.json.{*, given}
import io.scalaland.chimney.Transformer

case class ApiOriginal(
  id: OriginalId,
  storeId: StoreId,
  kind: MediaKind,
  cameraShootDateTime: Option[ShootDateTime],
  cameraName: Option[CameraName],
  artistInfo: Option[ArtistInfo],
  dimension: Option[ApiDimension],
  orientation: Option[Orientation],
  location: Option[ApiLocation],
  aperture: Option[Aperture],
  exposureTime: Option[ApiExposureTime],
  iso: Option[ISO],
  focalLength: Option[FocalLength]
) derives JsonCodec

given Transformer[Original, ApiOriginal] =
  Transformer
    .define[Original, ApiOriginal]
    .withFieldComputed(_.storeId, _.store.id)
    .buildTransformer
