package fr.janalyse.sotohp.store.dao

import zio.json.JsonCodec

import java.nio.file.Path

case class DaoMiniatureSource(
  path: String,
  dimension: DaoDimension2D
) derives JsonCodec
