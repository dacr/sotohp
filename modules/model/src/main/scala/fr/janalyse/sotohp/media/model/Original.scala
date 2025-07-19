package fr.janalyse.sotohp.media.model

import fr.janalyse.sotohp.media.model

import java.io.File
import java.nio.file.Path
import java.time.OffsetDateTime
import java.util.UUID
import scala.annotation.targetName

opaque type OriginalId = UUID
object OriginalId {
  def apply(id: UUID): OriginalId = id
  extension (originalId: OriginalId) {
    def asString: String = originalId.toString
  }
}

opaque type CameraName = String
object CameraName {
  def apply(name: String): CameraName = name
  extension (cameraName: CameraName) {
    def text: String = cameraName
  }
}

opaque type BaseDirectoryPath = Path
object BaseDirectoryPath {
  def apply(path: Path): BaseDirectoryPath = path
  extension (baseDirPath: BaseDirectoryPath) {
    def path: Path = baseDirPath
  }
}

opaque type OriginalPath = Path
object OriginalPath {
  def apply(path: Path): OriginalPath = path
  extension (originalPath: OriginalPath) {
    def parent: Path      = originalPath.getParent
    def file: File        = originalPath.toFile
    def path: Path        = originalPath
    def fileName: String  = originalPath.getFileName.toString
    def extension: String = originalPath.getFileName.toString.split("\\.").last
  }
}

opaque type ShootDateTime = OffsetDateTime
object ShootDateTime {
  def apply(timeStamp: OffsetDateTime): ShootDateTime = timeStamp
}
extension (shootDateTime: ShootDateTime) {
  def year: Int                      = shootDateTime.getYear
  def offsetDateTime: OffsetDateTime = shootDateTime
}

opaque type FileSize = Long
object FileSize {
  def apply(size: Long): FileSize = size
  extension (fileSize: FileSize) {
    def value: Long = fileSize
  }
}

opaque type FileLastModified = OffsetDateTime
object FileLastModified {
  def apply(timeStamp: OffsetDateTime): FileLastModified = timeStamp
  extension (fileLastModified: FileLastModified) {
    def offsetDateTime: OffsetDateTime = fileLastModified
  }
}

opaque type FileHash = String
object FileHash {
  def apply(hash: String): FileHash = hash
  extension (fileHash: FileHash) {
    def code: String = fileHash
  }
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
