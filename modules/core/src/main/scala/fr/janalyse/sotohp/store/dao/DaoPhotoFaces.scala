package fr.janalyse.sotohp.store.dao

import zio.json.JsonCodec

case class DaoDetectedFace(
  someoneId: Option[String],
  box: DaoBoundingBox
) derives JsonCodec

case class DaoPhotoFaces(
  faces: List[DaoDetectedFace],
  count: Int
) derives JsonCodec
