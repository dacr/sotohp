package fr.janalyse.sotohp.service.dao

import fr.janalyse.sotohp.service
import zio.lmdb.json.LMDBCodecJson

import java.time.OffsetDateTime
import fr.janalyse.sotohp.service.json.{*, given}
import zio.lmdb.schema.LMDBSchema

case class DaoProcessedStatus(
  successful: Boolean,
  timestamp: OffsetDateTime
) derives LMDBCodecJson, LMDBSchema
