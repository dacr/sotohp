package fr.janalyse.sotohp.service.dao

import fr.janalyse.sotohp.model.{Original, OriginalId}
import fr.janalyse.sotohp.processor.model.FaceId
import fr.janalyse.sotohp.service
import zio.lmdb.json.LMDBCodecJson
import fr.janalyse.sotohp.service.json.{given, *}

case class DaoFaceFeatures(
  faceId: FaceId,
  features: Array[Float]
) derives LMDBCodecJson

case class DaoOriginalFaceFeatures(
  originalId: OriginalId,
  status: DaoProcessedStatus
) derives LMDBCodecJson
