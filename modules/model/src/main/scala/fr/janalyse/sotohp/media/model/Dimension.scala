package fr.janalyse.sotohp.media.model

opaque type Width  = Int
object Width {
  def apply(width: Int): Width = width
  extension (width: Width) {
    def value: Int = width
  }
}

opaque type Height = Int
object Height {
  def apply(height: Int): Height = height
  extension (height: Height) {
    def value: Int = height
  }
}

case class Dimension(
  width: Width,
  height: Height
)
