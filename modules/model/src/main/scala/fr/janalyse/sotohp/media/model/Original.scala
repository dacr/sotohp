package fr.janalyse.sotohp.media.model

import fr.janalyse.sotohp.media.model

case class Original(
  id: OriginalId,
  store: Store,
  mediaPath: OriginalPath,
  fileHash: FileHash,
  fileSize: FileSize,
  fileLastModified: FileLastModified,
  cameraShootDateTime: Option[ShootDateTime],
  cameraName: Option[CameraName],
  artistInfo: Option[ArtistInfo],
  dimension: Option[Dimension],
  orientation: Option[Orientation],
  location: Option[Location],
  aperture: Option[Aperture],
  shutterSpeed: Option[ShutterSpeed],
  iso: Option[ISO],
  focalLength: Option[FocalLength],
)

case class OriginalCameraTags(
  originalId: OriginalId,
  tags: Map[String, String]
)
