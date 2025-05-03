package fr.janalyse.sotohp.store.dao

import zio.json.JsonCodec
import zio.lmdb.json.LMDBCodecJson

import java.time.OffsetDateTime

case class DaoMiniatures(
  sources: List[DaoMiniatureSource]
) derives LMDBCodecJson
