package fr.janalyse.sotohp.model

import java.time.OffsetDateTime

case class PhotoState(
  photoId: PhotoId,
  lastSynchronized: OffsetDateTime,
  lastUpdated: OffsetDateTime,
  firstSeen: OffsetDateTime
)
