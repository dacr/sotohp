package fr.janalyse.sotohp.service.model

import fr.janalyse.sotohp.model.StoreId
import zio.json.JsonCodec

import scala.util.matching.Regex

case class Rewriting(
  regex: String,
  replacement: String
) derives JsonCodec {
  lazy val pattern: Regex = regex.r
}

case class Mapping(
  from: String,
  to: String
) derives JsonCodec

case class KeywordRules(
  ignoring: Set[String],
  mappings: List[Mapping],
  rewritings: List[Rewriting]
) derives JsonCodec
