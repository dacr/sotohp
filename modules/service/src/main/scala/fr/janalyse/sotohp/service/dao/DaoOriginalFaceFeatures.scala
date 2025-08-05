package fr.janalyse.sotohp.service.dao

import fr.janalyse.sotohp.model.{Original, OriginalId}
import fr.janalyse.sotohp.processor.model.FaceId
import zio.lmdb.json.LMDBCodecJson

case class DaoFaceFeatures(
  faceId: FaceId,
  box: DaoBoundingBox,
  features: Array[Float]
) derives LMDBCodecJson

case class OriginalFaceFeatures(
  originalId: OriginalId,
  status: DaoProcessedStatus,
  features: List[DaoFaceFeatures]
) derives LMDBCodecJson
