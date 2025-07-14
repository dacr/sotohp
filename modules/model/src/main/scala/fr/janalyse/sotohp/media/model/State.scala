package fr.janalyse.sotohp.media.model

import java.time.OffsetDateTime

type LastSeen = OffsetDateTime
object LastSeen {
  def apply(date: OffsetDateTime): LastSeen = date
  extension (lastSeen: LastSeen) {
    def offsetDateTime: OffsetDateTime = lastSeen
  }
}

type LastSynchronized = OffsetDateTime
object LastSynchronized {
  def apply(date: OffsetDateTime): LastSynchronized = date
  extension (lastSynchronized: LastSynchronized) {
    def offsetDateTime: OffsetDateTime = lastSynchronized
  }
}

opaque type FirstSeen = OffsetDateTime
object FirstSeen {
  def apply(timeStamp: OffsetDateTime): FirstSeen = timeStamp
  extension (firstSeen: FirstSeen) {
    def offsetDateTime: OffsetDateTime = firstSeen
  }
}

case class State(
  originalId: OriginalId,
  firstSeen: FirstSeen,
  lastSeen: LastSeen,
  lastSynchronized: Option[LastSynchronized]
)
