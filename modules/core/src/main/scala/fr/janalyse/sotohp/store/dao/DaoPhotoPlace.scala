package fr.janalyse.sotohp.store.dao

import zio.json.*
import zio.lmdb.json.LMDBCodecJson

case class DaoPhotoPlace(
  latitude: Double,
  longitude: Double,
  altitude: Option[Double],
  deducted: Boolean
) derives LMDBCodecJson
