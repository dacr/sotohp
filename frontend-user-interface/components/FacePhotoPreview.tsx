"use client";

// Hover preview for face tiles: after a short pause over a face, show its timestamp and (once
// resolved) the full photo it was cropped from - resolving originalId -> mediaAccessKey ->
// miniature is async, so the popup appears immediately and grows the image in once it's loaded.
// Shared by FaceGrid (Persons tab) and the face clusters members view.
import { useEffect, useRef, useState, type ReactNode } from "react";
import { useAuth } from "../lib/keycloak-auth";
import { displayRotationDegrees, rotatedImageBox } from "../lib/orientation";
import type { DetectedFace, Media } from "../lib/api-client";

// `rotateDeg` / `natWidth` / `natHeight` land together with the miniature once it has loaded : the
// served rendition is only baked to the *original's* EXIF orientation, so a photo the user rotated
// by hand still needs the difference applied here, and rotating it needs its natural size (see
// rotatedImageBox).
export type FacePreviewState = {
  face: DetectedFace;
  x: number;
  y: number;
  miniatureUrl?: string;
  rotateDeg?: number;
  natWidth?: number;
  natHeight?: number;
};

const PREVIEW_IMAGE_MAX_SIDE = 250;
const PREVIEW_DELAY_MS = 600;

// Heuristic viewport clamp (flip to the other side of the cursor rather than overflow) - avoids
// a measure-then-reposition round trip for what's a small, low-stakes hover preview.
function clampPreviewPos(x: number, y: number): { left: number; top: number } {
  const estW = 270;
  const estH = 320;
  let left = x + 14;
  let top = y + 12;
  if (typeof window !== "undefined") {
    if (left + estW > window.innerWidth) left = x - estW - 14;
    if (top + estH > window.innerHeight) top = y - estH - 12;
  }
  return { left: Math.max(0, left), top: Math.max(0, top) };
}

/** Wire the returned handlers onto each face `<img>`, and render `<FacePhotoPreview preview={...} />` once. */
export function useFacePhotoPreview() {
  const { api } = useAuth();
  const [preview, setPreview] = useState<FacePreviewState | null>(null);
  const hoverTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  // The media, not just its access key : the popup needs the orientation too, and a grid this
  // size keeps coming back to the same handful of photos, so both are cached per originalId (null
  // caches the failures, so a photo without a media is not looked up on every hover).
  const mediaCacheRef = useRef<Map<string, Media | null>>(new Map());

  useEffect(
    () => () => {
      if (hoverTimerRef.current) clearTimeout(hoverTimerRef.current);
    },
    []
  );

  async function resolveMedia(originalId: string): Promise<Media | null> {
    const cached = mediaCacheRef.current.get(originalId);
    if (cached !== undefined) return cached;
    let media: Media | null = null;
    try {
      const state = await api.getState(originalId);
      if (state.mediaAccessKey) media = await api.getMediaByKey(state.mediaAccessKey);
    } catch {
      media = null;
    }
    mediaCacheRef.current.set(originalId, media);
    return media;
  }

  async function show(face: DetectedFace, x: number, y: number) {
    setPreview({ face, x, y });
    const media = await resolveMedia(face.originalId);
    if (!media) return;
    const url = api.mediaMiniatureUrl(media.accessKey);
    const rotateDeg = displayRotationDegrees(media);
    const preload = new Image();
    preload.onload = () => {
      setPreview((p) =>
        p && p.face.faceId === face.faceId ? { ...p, miniatureUrl: url, rotateDeg, natWidth: preload.naturalWidth, natHeight: preload.naturalHeight } : p
      );
    };
    preload.src = url;
  }

  function onMouseEnter(e: React.MouseEvent, face: DetectedFace) {
    const x = e.clientX;
    const y = e.clientY;
    if (hoverTimerRef.current) clearTimeout(hoverTimerRef.current);
    hoverTimerRef.current = setTimeout(() => show(face, x, y), PREVIEW_DELAY_MS);
  }
  function onMouseMove(e: React.MouseEvent, face: DetectedFace) {
    setPreview((p) => (p && p.face.faceId === face.faceId ? { ...p, x: e.clientX, y: e.clientY } : p));
  }
  function onMouseLeave() {
    if (hoverTimerRef.current) {
      clearTimeout(hoverTimerRef.current);
      hoverTimerRef.current = null;
    }
    setPreview(null);
  }

  return { preview, onMouseEnter, onMouseMove, onMouseLeave };
}

export function FacePhotoPreview({ preview, details, hint }: { preview: FacePreviewState | null; details?: ReactNode; hint: string }) {
  if (!preview) return null;
  const pos = clampPreviewPos(preview.x, preview.y);
  const deg = preview.rotateDeg ?? 0;
  return (
    <div className="mosaic-photo-tooltip show" style={{ left: pos.left, top: pos.top }}>
      <div className="title">{new Date(preview.face.timestamp).toLocaleString()}</div>
      {details}
      {preview.miniatureUrl &&
        (() => {
          const { boxWidth, boxHeight, imageWidth, imageHeight } = rotatedImageBox(preview.natWidth ?? 0, preview.natHeight ?? 0, deg, {
            maxWidth: PREVIEW_IMAGE_MAX_SIDE,
            maxHeight: PREVIEW_IMAGE_MAX_SIDE,
          });
          return (
            <div style={{ margin: "6px 0", position: "relative", width: boxWidth, height: boxHeight, borderRadius: 4, overflow: "hidden", background: "#000" }}>
              <img
                src={preview.miniatureUrl}
                alt=""
                style={{
                  position: "absolute",
                  left: "50%",
                  top: "50%",
                  width: imageWidth,
                  height: imageHeight,
                  display: "block",
                  transform: `translate(-50%, -50%) rotate(${deg}deg)`,
                }}
              />
            </div>
          );
        })()}
      <div className="subtitle">{hint}</div>
    </div>
  );
}
