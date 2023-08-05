package fr.janalyse.sotohp.store.dao

import zio.json.JsonCodec

case class DaoNormalizedPhoto(
  path: String,
  dimension: DaoDimension2D
) derives JsonCodec
