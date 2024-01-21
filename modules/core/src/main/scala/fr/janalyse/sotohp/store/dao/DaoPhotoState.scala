package fr.janalyse.sotohp.store.dao

import zio.json.JsonCodec

import java.time.OffsetDateTime

case class DaoPhotoState(
  photoId: String,
  originalId: String,
  photoHash: String,
  photoOwnerId: String,
  photoTimestamp: OffsetDateTime,
  lastSeen: OffsetDateTime,
  firstSeen: OffsetDateTime,
  lastSynchronized: Option[OffsetDateTime]
) derives JsonCodec
