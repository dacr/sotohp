package fr.janalyse.sotohp.service.dao

import fr.janalyse.sotohp.model.{FaceId, Original, OriginalId}
import fr.janalyse.sotohp.service
import zio.lmdb.json.LMDBCodecJson
import fr.janalyse.sotohp.service.json.{*, given}
import zio.lmdb.schema.LMDBSchema

// `model` : engine/model tag of the vector (None = historical DJL model, also what records
// written before the field existed decode to).
case class DaoFaceFeatures(
  faceId: FaceId,
  features: Array[Float],
  model: Option[String]
) derives LMDBCodecJson, LMDBSchema

case class DaoOriginalFaceFeatures(
  originalId: OriginalId,
  status: DaoProcessedStatus,
  model: Option[String]
) derives LMDBCodecJson, LMDBSchema
