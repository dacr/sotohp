package fr.janalyse.sotohp.media.model

import java.util.UUID
import scala.util.matching.Regex

opaque type StoreId = UUID
object StoreId {
  def apply(id: UUID): StoreId = id
  extension (storeId: StoreId) {
    def asString: String = storeId.toString
  }
}

opaque type IncludeMask = Regex
object IncludeMask {
  def apply(regex: Regex): IncludeMask = regex
  extension (includeMask: IncludeMask) {
    def isIncluded(path: String): Boolean = includeMask.findFirstIn(path).isDefined
    def regex: Regex                      = includeMask
  }
}

opaque type IgnoreMask = Regex
object IgnoreMask {
  def apply(regex: Regex): IgnoreMask = regex
  extension (ignoreMaskRegex: IgnoreMask) {
    def isIgnored(path: String): Boolean = ignoreMaskRegex.findFirstIn(path).isDefined
    def regex: Regex                     = ignoreMaskRegex
  }
}

case class Store(
  id: StoreId,
  ownerId: OwnerId,
  baseDirectory: BaseDirectoryPath,
  includeMask: Option[IncludeMask],
  ignoreMask: Option[IgnoreMask]
)
