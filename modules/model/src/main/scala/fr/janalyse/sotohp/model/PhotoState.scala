package fr.janalyse.sotohp.model

import java.time.OffsetDateTime

case class PhotoState(
  photoId: PhotoId,
  photoHash: PhotoHash,
  firstSeen: OffsetDateTime,
  lastSeen: OffsetDateTime,
  lastUpdated: OffsetDateTime,
  originalAddedOn: OffsetDateTime // typically initialized with file last modified (when first seen)
)
