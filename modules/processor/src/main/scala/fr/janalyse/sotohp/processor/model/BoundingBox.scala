package fr.janalyse.sotohp.processor.model

import fr.janalyse.sotohp.model.{Width, Height}

opaque type XAxis = Double
object XAxis {
  def apply(value: Double): XAxis = value
  extension (x: XAxis) {
    def value: Double = x
  }
}

opaque type YAxis = Double
object YAxis {
  def apply(value: Double): YAxis = value
  extension (y: YAxis) {
    def value: Double = y
  }
}

// -------------------------------------------------------------------------------------------------------------------
opaque type BoxWidth = Double

object BoxWidth {
  def apply(width: Double): BoxWidth = width

  extension (width: BoxWidth) {
    def value: Double = width
  }
}

// -------------------------------------------------------------------------------------------------------------------
opaque type BoxHeight = Double

object BoxHeight {
  def apply(height: Double): BoxHeight = height

  extension (height: BoxHeight) {
    def value: Double = height
  }
}

case class BoundingBox(
  x: XAxis,
  y: YAxis,
  width: BoxWidth,
  height: BoxHeight
)
