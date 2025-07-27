package fr.janalyse.sotohp.media.model

import fr.janalyse.sotohp.media.model

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
)

case class OriginalCameraTags(
  originalId: OriginalId,
  tags: Map[String, String]
)
