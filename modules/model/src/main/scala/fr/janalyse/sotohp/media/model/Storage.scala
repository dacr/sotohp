package fr.janalyse.sotohp.media.model

import java.util.UUID
import scala.util.matching.Regex

opaque type StorageId = UUID
object StorageId {
  def apply(id: UUID): StorageId = id
}

opaque type IncludeMask = Regex
object IncludeMask {
  def apply(regex: Regex): IncludeMask = regex
  extension (includeMask: IncludeMask) {
    def isIncluded(path: String): Boolean = includeMask.findFirstIn(path).isDefined
  }
}

opaque type IgnoreMask = Regex
object IgnoreMask {
  def apply(regex: Regex): IgnoreMask = regex
  extension (ignoreMaskRegex: IgnoreMask) {
    def isIgnored(path: String): Boolean = ignoreMaskRegex.findFirstIn(path).isDefined
  }
}

case class Storage(
  id: StorageId,
  ownerId: OwnerId,
  baseDirectory: BaseDirectoryPath,
  includeMask: Option[IncludeMask],
  ignoreMask: Option[IgnoreMask]
)
