// Orientation is serialized by the API as one of 8 named EXIF-style values. Mirror variants
// collapse to the same on-screen rotation as their non-mirrored counterpart — mirroring isn't
// rendered (no UI here flips images), only the rotation is. Shared by the viewer, mosaic, and
// portfolio asset editor/viewer, which all need "how many degrees to rotate this image".
import type { Media } from "./api-client";

export function orientationToDegrees(o: Media["orientation"] | undefined): 0 | 90 | 180 | 270 {
  switch (o) {
    case "Rotate90ClockWise":
    case "MirrorHorizontalAndRotate90ClockWise":
      return 90;
    case "Rotate180":
      return 180;
    case "Rotate270ClockWise":
    case "MirrorHorizontalAndRotate270ClockWise":
      return 270;
    default:
      return 0;
  }
}

export function degreesToOrientation(deg: number): Media["orientation"] {
  const normalized = ((deg % 360) + 360) % 360;
  switch (normalized) {
    case 90:
      return "Rotate90ClockWise";
    case 180:
      return "Rotate180";
    case 270:
      return "Rotate270ClockWise";
    default:
      return "Horizontal";
  }
}
