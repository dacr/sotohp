package fr.janalyse.sotohp.store.dao

import zio.json.JsonCodec
import zio.lmdb.json.LMDBCodecJson

import java.time.OffsetDateTime

case class DaoNormalizedPhoto(
  size: Int,
  dimension: DaoDimension2D
) derives LMDBCodecJson
