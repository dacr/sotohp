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
  // TODO add field for camera owner coming from exif
  dimension: Option[Dimension],
  orientation: Option[Orientation],
  location: Option[Location]
)

case class OriginalCameraTags(
  originalId: OriginalId,
  tags: Map[String, String]
)
