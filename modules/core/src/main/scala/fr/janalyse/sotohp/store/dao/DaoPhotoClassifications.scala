package fr.janalyse.sotohp.store.dao

import zio.json.JsonCodec

case class DaoDetectedClassification(
  name: String
) derives JsonCodec

case class DaoPhotoClassifications(
  classifications: List[DaoDetectedClassification]
) derives JsonCodec
