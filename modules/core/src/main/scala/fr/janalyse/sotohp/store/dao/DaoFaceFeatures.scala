package fr.janalyse.sotohp.store.dao

import zio.json.JsonCodec
import zio.lmdb.json.LMDBCodecJson

case class DaoFaceFeatures(
  photoId: String,
  someoneId: Option[String],
  box: DaoBoundingBox,
  features: Array[Float]
) derives LMDBCodecJson
