package fr.janalyse.sotohp.store.dao

import zio.json.JsonCodec

case class DaoFaceFeatures(
  photoId: String,
  someoneId: Option[String],
  box: DaoBoundingBox,
  features: Array[Float]
) derives JsonCodec
