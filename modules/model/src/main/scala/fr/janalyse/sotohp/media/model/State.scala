package fr.janalyse.sotohp.media.model

import java.time.OffsetDateTime

opaque type LastChecked = OffsetDateTime
object LastChecked {
  def apply(date: OffsetDateTime): LastChecked = date
  extension (lastChecked: LastChecked) {
    def offsetDateTime: OffsetDateTime = lastChecked
  }
}

opaque type LastSynchronized = OffsetDateTime
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
  mediaAccessKey: MediaAccessKey,
  firstSeen: FirstSeen,
  lastChecked: LastChecked,
  lastSynchronized: Option[LastSynchronized]
)
