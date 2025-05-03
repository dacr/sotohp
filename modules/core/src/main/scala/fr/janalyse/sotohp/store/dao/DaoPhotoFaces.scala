package fr.janalyse.sotohp.store.dao

import zio.json.JsonCodec
import zio.lmdb.json.LMDBCodecJson

case class DaoDetectedFace(
  someoneId: Option[String],
  box: DaoBoundingBox,
  faceId: String
) derives LMDBCodecJson

case class DaoPhotoFaces(
  faces: List[DaoDetectedFace],
  count: Int
) derives LMDBCodecJson
