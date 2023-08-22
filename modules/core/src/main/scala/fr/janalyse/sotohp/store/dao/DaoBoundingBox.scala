package fr.janalyse.sotohp.store.dao

import zio.json.JsonCodec

case class DaoBoundingBox(
  x: Double,
  y: Double,
  width: Double,
  height: Double
) derives JsonCodec
