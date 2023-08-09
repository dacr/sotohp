package fr.janalyse.sotohp.model

import java.nio.file.Path

case class MiniatureSource(
  path: Path,
  dimension: Dimension2D
) {
  def size = math.max(dimension.width, dimension.height)
}
