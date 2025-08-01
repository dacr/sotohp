package fr.janalyse.sotohp.model

enum Orientation(val code: Int, val description: String, val rotationDegrees: Int = 0) {
  case Horizontal                            extends Orientation(1, "Horizontal (normal)")
  case MirrorHorizontal                      extends Orientation(2, "Mirror horizontal")
  case Rotate180                             extends Orientation(3, "Rotate 180", 180)
  case MirrorVertical                        extends Orientation(4, "Mirror vertical")
  case MirrorHorizontalAndRotate270ClockWise extends Orientation(5, "Mirror horizontal and rotate 270 CW", 270)
  case Rotate90ClockWise                     extends Orientation(6, "Rotate 90 CW", 90)
  case MirrorHorizontalAndRotate90ClockWise  extends Orientation(7, "Mirror horizontal and rotate 90 CW", 90)
  case Rotate270ClockWise                    extends Orientation(8, "Rotate 270 CW", 270)
}
