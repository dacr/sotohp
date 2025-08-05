package fr.janalyse.sotohp.service.dao

import fr.janalyse.sotohp.model.{Original, OriginalId}
import fr.janalyse.sotohp.processor.model.{FaceId, OriginalFaces}
import io.scalaland.chimney.Transformer
import wvlet.airframe.ulid.ULID
import zio.lmdb.json.LMDBCodecJson

case class DaoDetectedFace(
  faceId: FaceId,
  box: DaoBoundingBox
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
