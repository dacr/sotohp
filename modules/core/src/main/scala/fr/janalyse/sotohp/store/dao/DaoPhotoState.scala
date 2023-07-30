package fr.janalyse.sotohp.store.dao

import zio.json.JsonCodec

import java.time.OffsetDateTime
import java.util.UUID

case class DaoPhotoState(
  photoId: UUID,
  lastSynchronized: OffsetDateTime,
  lastUpdated: OffsetDateTime,
  firstSeen: OffsetDateTime
) derives JsonCodec
