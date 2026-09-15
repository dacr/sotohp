package fr.janalyse.sotohp.search

import scala.io.Source
import scala.util.Using

/** French synonym groups used to expand free-text search queries (see
  * `ElasticOperations.searchMediaIds`) - e.g. a search for "bagnole" also searches "voiture", "auto"
  * and "automobile". Groups are loaded once from the `synonyms_fr.txt` classpath resource: one group
  * of comma-separated, interchangeable words per line. Only the query is expanded, never the indexed
  * documents, so adding or editing a group is just a redeploy, never a reindex.
  */
object FrenchSynonyms {

  private val resourceName = "synonyms_fr.txt"

  private val groups: List[Set[String]] = {
    val lines = Using.resource(Source.fromResource(resourceName))(_.getLines().toList)
    lines
      .map(_.trim)
      .filter(line => line.nonEmpty && !line.startsWith("#"))
      .map(_.split(",").iterator.map(word => TextNormalization.foldDiacritics(word.trim.toLowerCase)).filter(_.nonEmpty).toSet)
      .filter(_.size > 1) // a "group" of one word has nothing to expand to
  }

  // folded, lower-cased word -> every *other* word sharing one of its groups
  private val lookup: Map[String, Set[String]] =
    groups
      .flatMap(group => group.map(word => word -> (group - word)))
      .groupMapReduce(_._1)(_._2)(_ ++ _)

  /** The other words interchangeable with `word` (excluding `word` itself), or empty if it belongs
    * to no known group. Lookup is case-insensitive and diacritics-insensitive; `word` need not be
    * pre-folded.
    */
  def expand(word: String): Set[String] =
    lookup.getOrElse(TextNormalization.foldDiacritics(word.toLowerCase), Set.empty)
}
