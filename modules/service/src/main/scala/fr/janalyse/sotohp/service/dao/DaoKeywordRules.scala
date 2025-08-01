package fr.janalyse.sotohp.service.dao

import fr.janalyse.sotohp.model.StoreId
import fr.janalyse.sotohp.service.model.Rewriting
import zio.lmdb.json.LMDBCodecJson
import io.scalaland.chimney.Transformer

import scala.util.matching.compat.Regex

case class DaoRewriting(
  pattern: String,
  replacement: String
) derives LMDBCodecJson

object DaoRewriting {
  given Transformer[Rewriting, DaoRewriting] =
    Transformer
      .define[Rewriting, DaoRewriting]
      .withFieldComputed(_.pattern, _.pattern.toString)
      .buildTransformer
  given Transformer[DaoRewriting, Rewriting] =
    Transformer
      .define[DaoRewriting, Rewriting]
      .withFieldComputed(_.pattern, _.pattern.r)
      .buildTransformer
}

case class DaoKeywordRules(
  ignoring: Set[String],
  mappings: Map[String,String],
  rewritings: List[DaoRewriting]
) derives LMDBCodecJson
