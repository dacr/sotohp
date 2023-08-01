package fr.janalyse.sotohp.store.dao

import zio.json.*

case class DaoPhotoPlace(
  latitude: Double,
  longitude: Double,
  altitude: Double
) derives JsonCodec