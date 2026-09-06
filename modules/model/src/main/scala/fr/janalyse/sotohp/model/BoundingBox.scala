package fr.janalyse.sotohp.model

case class BoundingBox(
  x: XAxis,
  y: YAxis,
  width: BoxWidth,
  height: BoxHeight
) {

  /** Remaps this box (normalized 0..1 coordinates within its containing image) to the box that
    * describes the same physical region once the containing image has been rotated clockwise by
    * a multiple of 90°. Exact for axis-aligned boxes - no approximation, no data loss.
    *
    * @param quarterTurnsClockWise
    *   number of 90° clockwise turns applied to the image (may be negative or >3, normalized mod 4)
    */
  def rotatedClockwise90(quarterTurnsClockWise: Int): BoundingBox = {
    val turns = ((quarterTurnsClockWise % 4) + 4) % 4
    (1 to turns).foldLeft(this) { (box, _) =>
      // point transform for a single 90° clockwise turn : (px,py) -> (1-py, px)
      // applied to the box top-left corner and extents (derivation kept exact for axis-aligned rectangles)
      BoundingBox(
        x = XAxis(1d - box.y.value - box.height.value),
        y = YAxis(box.x.value),
        width = BoxWidth(box.height.value),
        height = BoxHeight(box.width.value)
      )
    }
  }
}
