package fr.janalyse.sotohp.model

import java.time.OffsetDateTime

case class PhotoState(
  photoId: PhotoId,
  photoHash: PhotoHash,
  firstSeen: OffsetDateTime,
  lastSeen: OffsetDateTime,
  lastUpdated: OffsetDateTime
)
