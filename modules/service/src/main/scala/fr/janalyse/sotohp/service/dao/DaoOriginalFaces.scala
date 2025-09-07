package fr.janalyse.sotohp.service.dao

import fr.janalyse.sotohp.model.{Original, OriginalId}
import fr.janalyse.sotohp.processor.model.{DetectedFacePath, FaceId, OriginalFaces}
import fr.janalyse.sotohp.service
import io.scalaland.chimney.Transformer
import wvlet.airframe.ulid.ULID
import zio.lmdb.json.LMDBCodecJson
import fr.janalyse.sotohp.service.json.{given,*}

case class DaoDetectedFace(
  faceId: FaceId,
  box: DaoBoundingBox,
  path: DetectedFacePath
) derives LMDBCodecJson

case class DaoOriginalFaces(
  originalId: OriginalId,
  status: DaoProcessedStatus,
  faces: List[DaoDetectedFace]
) derives LMDBCodecJson

object DaoOriginalFaces {
  given Transformer[OriginalFaces, DaoOriginalFaces] =
    Transformer
      .define[OriginalFaces, DaoOriginalFaces]
      .withFieldComputed(_.originalId, _.original.id)
      .buildTransformer
}
