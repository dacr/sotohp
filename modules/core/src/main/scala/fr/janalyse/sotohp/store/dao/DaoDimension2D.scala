package fr.janalyse.sotohp.store.dao
import zio.json.*

case class DaoDimension2D(
  width: Int,
  height: Int
) derives JsonCodec
