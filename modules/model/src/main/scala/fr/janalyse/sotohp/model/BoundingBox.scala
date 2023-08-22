package fr.janalyse.sotohp.model

type XAxis = Double
type YAxis = Double

case class BoundingBox(
  x: XAxis,
  y: YAxis,
  width: Double,
  height: Double
)
