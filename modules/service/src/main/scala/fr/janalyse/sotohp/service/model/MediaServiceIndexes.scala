package fr.janalyse.sotohp.service.model

import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.service.dao.*
import zio.lmdb.{LMDBCollection, LMDBIndex}

import java.time.Instant
import java.util.UUID

case class MediaServiceIndexes(
  originalIdByTimestamp: LMDBIndex[Instant, UUID]
)
