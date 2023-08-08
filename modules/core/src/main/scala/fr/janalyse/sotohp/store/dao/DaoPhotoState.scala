package fr.janalyse.sotohp.store.dao

import zio.json.JsonCodec

import java.time.OffsetDateTime

case class DaoPhotoState(
  photoId: String,
  photoHash: String,
  lastSynchronized: OffsetDateTime,
  lastUpdated: OffsetDateTime,
  firstSeen: OffsetDateTime
) derives JsonCodec
