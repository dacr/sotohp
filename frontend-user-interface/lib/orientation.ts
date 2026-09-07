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
//
// Two distinct rotations live here, and mixing them up is what makes face boxes drift off the
// faces (see displayRotationDegrees below) :
//   - the EFFECTIVE one, `media.orientation ?? media.original.orientation` (the user's override
//     wins over the camera's EXIF value). It is how the photo is meant to be seen, and it is the
//     frame every stored geometry uses : MediaServiceLive.facesRemapForRotation rewrites face
//     boxes into it on every orientation change, and ApiApp's mediaUpdateLogic computes that
//     rotation the very same way.
//   - the DISPLAY one, what the browser still has to rotate. The bytes we serve are not raw : the
//     normalized/miniature renditions are baked once by NormalizeProcessor with
//     `original.orientation` already applied, and the browser auto-applies the very same EXIF
//     value to the original ones (`image-orientation: from-image` is the CSS default). So the
//     original rotation is always already on screen, and only the difference is left to do.
import type { Media } from "./api-client";

type Oriented = Pick<Media, "orientation" | "original">;

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

// How the photo is meant to be seen, and the frame face boxes are stored in. `??` and not `||`:
// orientation 0 (Horizontal) is a real user choice - "undo the camera's EXIF rotation" - and must
// not fall back to the original's.
export function effectiveRotationDegrees(media: Oriented | undefined | null): 0 | 90 | 180 | 270 {
  if (!media) return 0;
  return orientationToDegrees(media.orientation ?? media.original?.orientation);
}

// Sizes an image that still has `rotateDeg` left to apply in the browser.
//
// A CSS rotation does not change the space an element reserves in layout, so a 90°-turned photo
// left to itself overflows its container on one axis and leaves a gap on the other. The caller
// therefore needs two different sizes : the box the *rotated* image ends up occupying, and the size
// to give the (still unrotated) <img> inside it. Put the <img> at 50%/50% of a `position:relative`
// box of boxWidth x boxHeight with `transform: translate(-50%, -50%) rotate(<deg>deg)`.
//
// `maxWidth`/`maxHeight` bound the *rotated* box (pass Infinity for an unbounded axis); upscaling a
// rendition past its natural size only blurs it, so it is opt-in.
export function rotatedImageBox(
  naturalWidth: number,
  naturalHeight: number,
  rotateDeg: number,
  fit: { maxWidth: number; maxHeight: number; allowUpscale?: boolean }
): { boxWidth: number; boxHeight: number; imageWidth: number; imageHeight: number } {
  if (!(naturalWidth > 0) || !(naturalHeight > 0)) return { boxWidth: 0, boxHeight: 0, imageWidth: 0, imageHeight: 0 };
  const swapped = rotateDeg === 90 || rotateDeg === 270;
  const rotatedWidth = swapped ? naturalHeight : naturalWidth;
  const rotatedHeight = swapped ? naturalWidth : naturalHeight;
  let scale = Math.min(fit.maxWidth / rotatedWidth, fit.maxHeight / rotatedHeight);
  if (!fit.allowUpscale) scale = Math.min(scale, 1);
  return {
    boxWidth: rotatedWidth * scale,
    boxHeight: rotatedHeight * scale,
    imageWidth: naturalWidth * scale,
    imageHeight: naturalHeight * scale,
  };
}

// What is left to rotate on screen, on top of the rotation the served image already carries.
// Without the subtraction a media whose user-set orientation differs from its EXIF one renders at
// the wrong angle, and - worse, because it is silent - its face boxes, which are expressed in the
// effective frame, land on an image that is not in that frame.
export function displayRotationDegrees(media: Oriented | undefined | null): 0 | 90 | 180 | 270 {
  if (!media) return 0;
  const baked = orientationToDegrees(media.original?.orientation);
  const delta = ((effectiveRotationDegrees(media) - baked) % 360 + 360) % 360;
  return delta as 0 | 90 | 180 | 270;
}
