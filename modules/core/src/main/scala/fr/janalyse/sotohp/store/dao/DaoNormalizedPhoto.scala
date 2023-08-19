package fr.janalyse.sotohp.store.dao

import zio.json.JsonCodec

import java.time.OffsetDateTime

case class DaoNormalizedPhoto(
  size: Int,
  dimension: DaoDimension2D
) derives JsonCodec
