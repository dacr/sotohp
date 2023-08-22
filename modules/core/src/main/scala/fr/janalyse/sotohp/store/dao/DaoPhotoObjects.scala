package fr.janalyse.sotohp.store.dao

import zio.json.JsonCodec

case class DaoDetectedObject(
  name: String,
  box: DaoBoundingBox
) derives JsonCodec

case class DaoPhotoObjects(
  objects: List[DaoDetectedObject]
) derives JsonCodec
