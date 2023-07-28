package fr.janalyse.sotohp.model

type XAxis = Int
type YAxis = Int

case class BoundingBox(
  x: XAxis,
  y: YAxis,
  dimension: Dimension2D
)
