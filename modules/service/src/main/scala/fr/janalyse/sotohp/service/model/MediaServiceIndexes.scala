package fr.janalyse.sotohp.service.model

import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.service.dao.*
import zio.lmdb.{LMDBCollection, LMDBIndex}

import java.time.Instant
import java.util.UUID
import zio.*

case class MediaServiceIndexes(
  collections: MediaServiceCollections,
  originalIdByTimestamp: LMDBIndex[Instant, UUID]
) {
  def rebuildOriginalIdByTimestampIndex() = {
  }

  def rebuildAllIndexes(): Unit = {
    rebuildOriginalIdByTimestampIndex()
  }
}
