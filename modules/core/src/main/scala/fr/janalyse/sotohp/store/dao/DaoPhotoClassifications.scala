package fr.janalyse.sotohp.store.dao

import zio.json.JsonCodec
import zio.lmdb.json.LMDBCodecJson

case class DaoDetectedClassification(
  name: String
) derives LMDBCodecJson

case class DaoPhotoClassifications(
  classifications: List[DaoDetectedClassification]
) derives LMDBCodecJson
