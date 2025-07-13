package fr.janalyse.sotohp.media.model

import fr.janalyse.sotohp.media.model

import java.io.File
import java.nio.file.Path
import java.time.OffsetDateTime
import java.util.UUID
import scala.annotation.targetName

opaque type BaseDirectoryPath = Path
opaque type OriginalPath      = Path
opaque type FileSize          = Long
opaque type FileLastModified  = OffsetDateTime
opaque type FileHash          = String
opaque type OriginalId        = UUID
opaque type ShootDateTime     = OffsetDateTime
opaque type CameraName        = String

object OriginalId {
  def apply(id: UUID): OriginalId = id
}

object CameraName {
  def apply(name: String): CameraName = name
}

extension (cameraName: CameraName) {
  def text: String = cameraName
}

object BaseDirectoryPath {
  def apply(path: Path): BaseDirectoryPath = path
}
extension (baseDirPath: BaseDirectoryPath) {
  def path: Path = baseDirPath
}

object OriginalPath {
  def apply(path: Path): OriginalPath = path
}
extension (originalPath: OriginalPath) {
  def parent: Path = originalPath.getParent
  @targetName("file_OriginPath")
  def file: File = originalPath.toFile
  @targetName("path_OriginPath")
  def path: Path = originalPath
  @targetName("fileName_OriginPath")
  def fileName: String = originalPath.getFileName.toString
  @targetName("extension_OriginPath")
  def extension: String = originalPath.getFileName.toString.split("\\.").last
}

object ShootDateTime {
  def apply(timeStamp: OffsetDateTime): ShootDateTime = timeStamp
}
extension (shootDateTime: ShootDateTime) {
  def year: Int = shootDateTime.getYear
  def offsetDateTime:OffsetDateTime = shootDateTime
}

object FileSize {
  def apply(size: Long): FileSize = size
}

object FileLastModified {
  def apply(timeStamp: OffsetDateTime): FileLastModified = timeStamp
}
extension (fileLastModified:FileLastModified) {
  @targetName("offsetDateTime_FileLastModified")
  def offsetDateTime:OffsetDateTime = fileLastModified
}

object FileHash {
  def apply(hash: String): FileHash = hash
}

extension (fileHash: FileHash) {
  def code: String = fileHash
}


case class Original(
  id: OriginalId,
  baseDirectory: BaseDirectoryPath,
  mediaPath: OriginalPath,
  ownerId: OwnerId,
  fileHash: FileHash,
  fileSize: FileSize,
  fileLastModified: FileLastModified,
  cameraShootDateTime: Option[ShootDateTime],
  cameraName: Option[CameraName],
  dimension: Option[Dimension],
  orientation: Option[Orientation],
  location: Option[Location]
)

case class OriginalCameraTags(
  originalId: OriginalId,
  tags: Map[String, String]
)
