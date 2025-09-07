package fr.janalyse.sotohp.api.protocol

import fr.janalyse.sotohp.model.{Aperture, ArtistInfo, CameraName, Dimension, ExposureTime, FileLastModified, FileSize, FocalLength, ISO, Location, MediaKind, Orientation, Original, OriginalId, OriginalPath, ShootDateTime, Store, StoreId}
import zio.json.{DeriveJsonCodec, JsonCodec, jsonHint}
import fr.janalyse.sotohp.service.json.{*, given}
import io.scalaland.chimney.Transformer
import sttp.tapir.Schema
import sttp.tapir.Schema.annotations.encodedName

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
)

object ApiOriginal {
  given Transformer[Original, ApiOriginal] =
    Transformer
      .define[Original, ApiOriginal]
      .withFieldComputed(_.storeId, _.store.id)
      .buildTransformer
  given JsonCodec[ApiOriginal] = DeriveJsonCodec.gen
  given Schema[ApiOriginal] = Schema.derived[ApiOriginal].name(Schema.SName("Original"))
}
