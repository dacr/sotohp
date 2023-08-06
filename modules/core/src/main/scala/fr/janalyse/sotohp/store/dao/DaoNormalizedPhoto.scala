package fr.janalyse.sotohp.store.dao

import zio.json.JsonCodec

import java.time.OffsetDateTime

case class DaoNormalizedPhoto(
  path: String,
  dimension: DaoDimension2D,
  lastUpdated: OffsetDateTime
) derives JsonCodec
