package fr.janalyse.sotohp.service.model

import fr.janalyse.sotohp.model.StoreId

import scala.util.matching.Regex

case class Rewriting(
  regex: String,
  replacement: String
) {
  lazy val pattern: Regex = regex.r
}

case class Mapping(
  from: String,
  to: String
)

case class KeywordRules(
  ignoring: Set[String],
  mappings: List[Mapping],
  rewritings: List[Rewriting]
)
