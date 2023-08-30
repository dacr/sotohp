package fr.janalyse.sotohp.store.dao

import zio.json.JsonCodec

import java.time.OffsetDateTime

case class DaoPhotoState(
  photoId: String,
  originalId: String,
  photoHash: String,
  photoOwnerId: String,
  photoTimestamp: OffsetDateTime,
  lastSynchronized: OffsetDateTime,
  lastUpdated: OffsetDateTime,
  firstSeen: OffsetDateTime,
  originalAddedOn: OffsetDateTime
) derives JsonCodec
