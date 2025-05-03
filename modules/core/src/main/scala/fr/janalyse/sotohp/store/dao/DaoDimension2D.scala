package fr.janalyse.sotohp.store.dao
import zio.json.*
import zio.lmdb.json.LMDBCodecJson

case class DaoDimension2D(
  width: Int,
  height: Int
) derives LMDBCodecJson
