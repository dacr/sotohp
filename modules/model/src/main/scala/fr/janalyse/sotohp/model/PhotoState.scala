package fr.janalyse.sotohp.model

import java.time.OffsetDateTime

case class PhotoState(
  photoId: PhotoId,
  photoHash: PhotoHash,
  lastSynchronized: OffsetDateTime,
  lastUpdated: OffsetDateTime,
  firstSeen: OffsetDateTime
)
