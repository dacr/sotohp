package fr.janalyse.sotohp.search

import zio.test.*

object FrenchSynonymsSpec extends ZIOSpecDefault {

  override def spec =
    suite("FrenchSynonyms")(
      test("expands a word to the other members of its group") {
        assertTrue(FrenchSynonyms.expand("bagnole") == Set("voiture", "auto", "automobile", "caisse"))
      },
      test("expansion is symmetric within a group") {
        assertTrue(FrenchSynonyms.expand("voiture").contains("bagnole"))
      },
      test("is case and diacritics insensitive") {
        assertTrue(FrenchSynonyms.expand("BAGNOLE") == FrenchSynonyms.expand("bagnole"))
      },
      test("a group with more than two words expands to every other member") {
        assertTrue(FrenchSynonyms.expand("gosse") == Set("enfant", "mioche", "gamin", "gamine"))
      },
      test("an unknown word has no synonyms") {
        assertTrue(FrenchSynonyms.expand("elephant").isEmpty)
      }
    )
}
