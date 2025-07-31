package fr.janalyse.sotohp.service.model

import fr.janalyse.sotohp.media.model.StoreId

import scala.util.matching.Regex

case class Rewriting(
  pattern: Regex,
  replacement: String
)

case class KeywordRules(
  storeId: StoreId,
  ignoring: Set[String],
  mappings: Map[String, String],
  rewritings: List[Rewriting]
)
