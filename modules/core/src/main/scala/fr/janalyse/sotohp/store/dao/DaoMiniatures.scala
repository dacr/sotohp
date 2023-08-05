package fr.janalyse.sotohp.store.dao

import zio.json.JsonCodec

import java.time.OffsetDateTime

case class DaoMiniatures(
  sources: List[DaoMiniatureSource],
  lastUpdated: OffsetDateTime
) derives JsonCodec
