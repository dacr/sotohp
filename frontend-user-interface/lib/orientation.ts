// Orientation is serialized by the API as the *integer ordinal* of the Scala Orientation enum
// (see modules/service/.../json/package.scala: `orientationCodec` writes `x.ordinal`). The OpenAPI
// schema now documents it as an int too (see protocol/package.scala's Schema[Orientation]), so
// api-types.ts's `Media["orientation"]` is a plain `number` matching the real wire format. Ordinal
// order: 0 Horizontal, 1 MirrorHorizontal, 2 Rotate180, 3 MirrorVertical,
// 4 MirrorHorizontalAndRotate270CW, 5 Rotate90CW, 6 MirrorHorizontalAndRotate90CW, 7 Rotate270CW.
//
// Mirror variants collapse to the same on-screen rotation as their non-mirrored counterpart -
// mirroring isn't rendered (no UI here flips images), only the rotation is. Shared by the viewer,
// mosaic, and portfolio asset editor/viewer, which all need "how many degrees to rotate this image".
import type { Media } from "./api-client";

export function orientationToDegrees(o: Media["orientation"] | undefined): 0 | 90 | 180 | 270 {
  switch (o) {
    case 5: // Rotate90ClockWise
    case 6: // MirrorHorizontalAndRotate90ClockWise
      return 90;
    case 2: // Rotate180
      return 180;
    case 4: // MirrorHorizontalAndRotate270ClockWise
    case 7: // Rotate270ClockWise
      return 270;
    default:
      return 0;
  }
}

export function degreesToOrientation(deg: number): Media["orientation"] {
  const normalized = ((deg % 360) + 360) % 360;
  return normalized === 90 ? 5 : normalized === 180 ? 2 : normalized === 270 ? 7 : 0;
}
