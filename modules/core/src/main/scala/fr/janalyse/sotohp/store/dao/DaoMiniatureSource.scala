package fr.janalyse.sotohp.store.dao

import zio.json.JsonCodec

import java.nio.file.Path

case class DaoMiniatureSource(
  size: Int,
  dimension: DaoDimension2D
) derives JsonCodec
