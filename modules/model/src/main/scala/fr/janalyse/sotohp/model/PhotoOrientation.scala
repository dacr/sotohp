package fr.janalyse.sotohp.model

enum PhotoOrientation(val code: Int, val description: String) {
  case Horizontal                            extends PhotoOrientation(1, "Horizontal (normal)")
  case MirrorHorizontal                      extends PhotoOrientation(2, "Mirror horizontal")
  case Rotate180                             extends PhotoOrientation(3, "Rotate 180")
  case MirrorVertical                        extends PhotoOrientation(4, "Mirror vertical")
  case MirrorHorizontalAndRotate270ClockWise extends PhotoOrientation(5, "Mirror horizontal and rotate 270 CW")
  case Rotate90ClockWise                     extends PhotoOrientation(6, "Rotate 90 CW")
  case MirrorHorizontalAndRotate90ClockWise  extends PhotoOrientation(7, "Mirror horizontal and rotate 90 CW")
  case Rotate270ClockWise                    extends PhotoOrientation(8, "Rotate 270 CW")
}
