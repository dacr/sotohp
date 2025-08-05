package fr.janalyse.sotohp.service.dao

import zio.lmdb.json.LMDBCodecJson

import java.time.OffsetDateTime

case class DaoProcessedStatus(
  successful: Boolean,
  timestamp: OffsetDateTime
) derives LMDBCodecJson
