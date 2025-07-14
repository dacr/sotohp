package fr.janalyse.sotohp.media

package object model {

  opaque type Keyword = String
  object Keyword {
    def apply(keyword: String): Keyword = keyword
    extension (keyword: Keyword) {
      def text: String = keyword
    }
  }

}
