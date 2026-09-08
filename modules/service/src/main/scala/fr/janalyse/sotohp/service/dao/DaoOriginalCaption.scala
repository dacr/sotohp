package fr.janalyse.sotohp.service.dao

import fr.janalyse.sotohp.model.OriginalId
import fr.janalyse.sotohp.processor.model.OriginalCaption
import fr.janalyse.sotohp.service
import io.scalaland.chimney.Transformer
import zio.lmdb.json.LMDBCodecJson
import fr.janalyse.sotohp.service.json.{*, given}
import zio.lmdb.schema.LMDBSchema

/** The model-generated caption ("auto description") for a photo, keyed by `OriginalId`.
  *
  * `text` is `None` when captioning failed or the captioner is disabled - `status.successful`
  * says which, and gates recomputation.
  */
case class DaoOriginalCaption(
  originalId: OriginalId,
  status: DaoProcessedStatus,
  text: Option[String]
) derives LMDBCodecJson, LMDBSchema

object DaoOriginalCaption {
  given Transformer[OriginalCaption, DaoOriginalCaption] =
    Transformer
      .define[OriginalCaption, DaoOriginalCaption]
      .withFieldComputed(_.originalId, _.original.id)
      .withFieldComputed(_.text, _.caption)
      .buildTransformer
}
