package fr.janalyse.sotohp.service.dao

import fr.janalyse.sotohp.model.{Original, OriginalId}
import fr.janalyse.sotohp.model.{FacePath, FaceId, PersonId}
import fr.janalyse.sotohp.processor.model.OriginalFaces
import fr.janalyse.sotohp.service
import io.scalaland.chimney.Transformer
import wvlet.airframe.ulid.ULID
import zio.lmdb.json.LMDBCodecJson
import fr.janalyse.sotohp.service.json.{*, given}

case class DaoOriginalFaces(
  originalId: OriginalId,
  status: DaoProcessedStatus,
  facesIds: List[FaceId]
) derives LMDBCodecJson

object DaoOriginalFaces {
  given Transformer[OriginalFaces, DaoOriginalFaces] =
    Transformer
      .define[OriginalFaces, DaoOriginalFaces]
      .withFieldComputed(_.originalId, _.original.id)
      //.withFieldComputed(_.facesIds, of => of.faces.map(_.faceId))
      .withFieldComputed(_.facesIds, of => of.faces.map(_.faceId))
      .buildTransformer
}
