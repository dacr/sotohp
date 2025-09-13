package fr.janalyse.sotohp.model

import fr.janalyse.sotohp.model

import java.time.OffsetDateTime

case class Original(
  id: OriginalId,
  store: Store,
  mediaPath: OriginalPath,
  fileSize: FileSize,
  fileLastModified: FileLastModified,
  kind: MediaKind,
  cameraShootDateTime: Option[ShootDateTime],
  cameraName: Option[CameraName],
  artistInfo: Option[ArtistInfo],
  dimension: Option[Dimension],
  orientation: Option[Orientation],
  location: Option[Location],
  aperture: Option[Aperture],
  exposureTime: Option[ExposureTime],
  iso: Option[ISO],
  focalLength: Option[FocalLength]
) {
  def timestamp: OffsetDateTime =
    cameraShootDateTime
      .map(_.offsetDateTime)
      .getOrElse(fileLastModified.offsetDateTime)
  def hasLocation: Boolean =
    location.isDefined && location.exists(l => l.latitude.doubleValue != 0d && l.longitude.doubleValue != 0d) // TODO fix location data
}
