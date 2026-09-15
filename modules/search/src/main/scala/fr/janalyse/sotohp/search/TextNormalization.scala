package fr.janalyse.sotohp.search

/** Small text-normalization helpers shared by the search query builder (`ElasticOperations`) and the
  * synonym dictionary (`FrenchSynonyms`) - kept together so both agree on what "the same word" means.
  */
object TextNormalization {

  /** Strips diacritics (é -> e, ñ -> n, ...) via NFD decomposition + dropping the combining marks. */
  def foldDiacritics(word: String): String =
    java.text.Normalizer.normalize(word, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "")
}
