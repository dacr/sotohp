package fr.janalyse.sotohp.service.dao

import fr.janalyse.sotohp.model.{FaceId, Original, OriginalId}
import fr.janalyse.sotohp.service
import zio.lmdb.json.LMDBCodecJson
import fr.janalyse.sotohp.service.json.{*, given}
import zio.lmdb.schema.LMDBSchema

case class DaoFaceFeatures(
  faceId: FaceId,
  features: Array[Float]
) derives LMDBCodecJson, LMDBSchema

case class DaoOriginalFaceFeatures(
  originalId: OriginalId,
  status: DaoProcessedStatus
) derives LMDBCodecJson, LMDBSchema
