package fr.janalyse.sotohp.store.dao

import zio.json.JsonCodec
import zio.lmdb.json.LMDBCodecJson

case class DaoBoundingBox(
  x: Double,
  y: Double,
  width: Double,
  height: Double
) derives LMDBCodecJson
