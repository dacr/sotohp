package fr.janalyse.sotohp.service.model

import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.service.dao.*
import zio.lmdb.{LMDBCollection, LMDBIndex}

import java.time.Instant
import java.util.UUID
import zio.*

case class MediaServiceIndexes(
  collections: MediaServiceCollections,
  originalIdByTimestamp: LMDBIndex[(Instant,OriginalId), OriginalId],
  originalIdByEventId: LMDBIndex[EventId, (Instant, OriginalId)],
  faceIdByPersonId: LMDBIndex[PersonId, (Instant, FaceId)]
) {
  def rebuildOriginalIdByTimestampIndex() = {}
  def rebuildOriginalIdByEventId() = {}
  def rebuildFaceIdByPersonId() = {}

  def rebuildAllIndexes(): Unit = {
    rebuildOriginalIdByTimestampIndex()
  }
}
