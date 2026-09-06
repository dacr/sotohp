"use client";

// Shared face-tile grid for both the per-person faces view (identified / to-validate modes) and
// the cross-person "all inferred faces" review queue.
import { useEffect, useRef, useState, type Dispatch, type SetStateAction } from "react";
import { useAuth } from "../lib/keycloak-auth";
import type { DetectedFace, Person } from "../lib/api-client";

export interface ImageRect {
  left: number;
  top: number;
  width: number;
  height: number;
}

type Tooltip = { face: DetectedFace; x: number; y: number; miniatureUrl?: string };

function personLabel(p: Person | undefined): { first: string; full: string } {
  if (!p) return { first: "", full: "" };
  const first = (p.firstName || "").trim();
  const last = (p.lastName || "").trim();
  return { first, full: `${first}${last ? " " + last : ""}` };
}

// Heuristic viewport clamp (flip to the other side of the cursor rather than overflow) - avoids
// a measure-then-reposition round trip for what's a small, low-stakes hover preview.
function clampTooltipPos(x: number, y: number): { left: number; top: number } {
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

export function FaceGrid({
  faces,
  mode,
  scopePersonId,
  personsMap,
  selected,
  onSelectedChange,
  onConfirmInferred,
  onIgnore,
  onRestore,
  onEdit,
  onOpenViewer,
  isLoading,
}: {
  faces: DetectedFace[];
  mode: "identified" | "validate";
  scopePersonId: string | null; // null = cross-person "all inferred" view
  personsMap: Map<string, Person>;
  selected: Set<string>;
  onSelectedChange: Dispatch<SetStateAction<Set<string>>>;
  onConfirmInferred: (face: DetectedFace) => void;
  onIgnore: (face: DetectedFace) => void;
  onRestore: (face: DetectedFace) => void;
  onEdit: (face: DetectedFace) => void;
  // The faces query hasn't resolved yet - render a neutral "Loading…" instead of the "no faces"
  // empty state, which otherwise flashes before the first fetch settles.
  isLoading?: boolean;
  onOpenViewer: (face: DetectedFace) => void;
}) {
  const { api } = useAuth();

  // Drag-to-select: mousedown on a tile starts a paint gesture (add or remove, decided by that
  // first tile's new state, or by a shift-click range) that mouseenter on subsequent tiles
  // continues until mouseup - lets you sweep the mouse across many faces instead of click/shift-
  // clicking each one.
  const dragIntentRef = useRef<boolean | null>(null);
  const lastIndexRef = useRef<number>(-1);

  // Hover preview: after a short pause over a face, show its timestamp and (once resolved) the
  // full photo it was cropped from - resolving originalId -> mediaAccessKey -> miniature is async,
  // so the tooltip appears immediately and grows the image in once it's loaded. Cached per
  // originalId since a grid this size often has many faces from the same handful of photos.
  const [tooltip, setTooltip] = useState<Tooltip | null>(null);
  const hoverTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const accessKeyCacheRef = useRef<Map<string, string>>(new Map());

  useEffect(
    () => () => {
      if (hoverTimerRef.current) clearTimeout(hoverTimerRef.current);
    },
    []
  );

  async function showTooltip(face: DetectedFace, x: number, y: number) {
    setTooltip({ face, x, y });
    let accessKey = accessKeyCacheRef.current.get(face.originalId);
    if (!accessKey) {
      try {
        const state = await api.getState(face.originalId);
        if (!state.mediaAccessKey) return;
        accessKey = state.mediaAccessKey;
        accessKeyCacheRef.current.set(face.originalId, accessKey);
      } catch {
        return;
      }
    }
    const url = api.mediaMiniatureUrl(accessKey);
    const preload = new Image();
    preload.onload = () => {
      setTooltip((t) => (t && t.face.faceId === face.faceId ? { ...t, miniatureUrl: url } : t));
    };
    preload.src = url;
  }

  function handleImgMouseEnter(e: React.MouseEvent, face: DetectedFace) {
    const x = e.clientX;
    const y = e.clientY;
    if (hoverTimerRef.current) clearTimeout(hoverTimerRef.current);
    hoverTimerRef.current = setTimeout(() => showTooltip(face, x, y), 600);
  }
  function handleImgMouseMove(e: React.MouseEvent, face: DetectedFace) {
    setTooltip((t) => (t && t.face.faceId === face.faceId ? { ...t, x: e.clientX, y: e.clientY } : t));
  }
  function handleImgMouseLeave() {
    if (hoverTimerRef.current) {
      clearTimeout(hoverTimerRef.current);
      hoverTimerRef.current = null;
    }
    setTooltip(null);
  }

  function applySelection(faceId: string, add: boolean) {
    onSelectedChange((prev) => {
      if (prev.has(faceId) === add) return prev;
      const next = new Set(prev);
      add ? next.add(faceId) : next.delete(faceId);
      return next;
    });
  }

  function handleTileMouseDown(e: React.MouseEvent, face: DetectedFace, index: number) {
    if (mode !== "validate") return;
    if ((e.target as HTMLElement).closest("button")) return;
    e.preventDefault();
    if (e.shiftKey && lastIndexRef.current >= 0) {
      const [start, end] = lastIndexRef.current < index ? [lastIndexRef.current, index] : [index, lastIndexRef.current];
      onSelectedChange((prev) => {
        const next = new Set(prev);
        for (let i = start; i <= end; i++) next.add(faces[i].faceId);
        return next;
      });
      dragIntentRef.current = true;
    } else {
      const add = !selected.has(face.faceId);
      applySelection(face.faceId, add);
      dragIntentRef.current = add;
      lastIndexRef.current = index;
    }
    function endDrag() {
      dragIntentRef.current = null;
      window.removeEventListener("mouseup", endDrag);
    }
    window.addEventListener("mouseup", endDrag, { once: true });
  }

  function handleTileMouseEnter(face: DetectedFace) {
    if (mode !== "validate" || dragIntentRef.current === null) return;
    applySelection(face.faceId, dragIntentRef.current);
  }

  function handleTileKeyDown(e: React.KeyboardEvent, face: DetectedFace, index: number) {
    if (mode !== "validate" || (e.key !== "Enter" && e.key !== " ")) return;
    e.preventDefault();
    applySelection(face.faceId, !selected.has(face.faceId));
    lastIndexRef.current = index;
  }

  if (faces.length === 0) {
    if (isLoading) return <div className="status muted">Loading…</div>;
    const msg = mode === "validate" && !scopePersonId ? "No inferred faces found." : "No faces found for this person";
    return <div className="status muted">{msg}</div>;
  }

  function personName(id: string | undefined | null): string {
    if (!id) return "";
    const p = personsMap.get(id);
    return p ? `${p.firstName || ""} ${p.lastName || ""}`.trim() : "";
  }

  const tooltipInferredName = tooltip && !scopePersonId ? personName(tooltip.face.inferredIdentifiedPersonId) : "";
  const tooltipHint = mode === "validate" ? "Click to select · Shift-click range · Drag to multi-select" : "Click to open in Viewer";

  return (
    <div className="person-faces-grid">
      {faces.map((face, index) => {
        const isSelected = selected.has(face.faceId);
        const isInferredForScope = !face.identifiedPersonId && (scopePersonId ? face.inferredIdentifiedPersonId === scopePersonId : !!face.inferredIdentifiedPersonId);
        return (
          <div
            key={face.faceId}
            className={`face-tile${face.inferredIgnore ? " face-ignored" : ""}`}
            style={mode === "validate" ? { cursor: "crosshair", outline: isSelected ? "3px solid #2563eb" : undefined, userSelect: "none" } : { cursor: "pointer" }}
            tabIndex={0}
            onMouseDown={(e) => handleTileMouseDown(e, face, index)}
            onMouseEnter={() => handleTileMouseEnter(face)}
            onKeyDown={(e) => handleTileKeyDown(e, face, index)}
            onClick={(e) => {
              if ((e.target as HTMLElement).closest("button")) return;
              if (mode === "validate") return; // handled on mousedown/mouseenter above
              onOpenViewer(face);
            }}
            title={mode === "validate" ? "Click to select · Shift-click for a range · Drag across tiles to multi-select" : "Click to open in Viewer"}
          >
            <img
              className="face-img"
              src={api.faceImageUrl(face.faceId)}
              alt="face"
              loading="lazy"
              decoding="async"
              draggable={false}
              onMouseEnter={(e) => handleImgMouseEnter(e, face)}
              onMouseMove={(e) => handleImgMouseMove(e, face)}
              onMouseLeave={handleImgMouseLeave}
            />
            {mode === "validate" && (
              <button type="button" className="ft-view" title="Open media in viewer" aria-label="View" onClick={() => onOpenViewer(face)}>
                🔍
              </button>
            )}
            <button type="button" className="ft-edit" title="Edit" aria-label="Edit" onClick={() => onEdit(face)}>
              ✎
            </button>
            {isInferredForScope && (
              <button
                type="button"
                className="face-badge inferred"
                title="Click to confirm identification"
                onClick={() => onConfirmInferred(face)}
                style={!scopePersonId ? { maxWidth: "100%", whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" } : undefined}
              >
                {scopePersonId ? "inferred" : personName(face.inferredIdentifiedPersonId) || "inferred"}
              </button>
            )}
            {mode === "validate" &&
              (face.inferredIgnore ? (
                <button type="button" className="face-badge restore" title="Restore this ignored face" onClick={() => onRestore(face)}>
                  ↩ restore
                </button>
              ) : (
                <button type="button" className="ft-ignore" title="Ignore this face" onClick={() => onIgnore(face)}>
                  🚫
                </button>
              ))}
          </div>
        );
      })}
      {tooltip &&
        (() => {
          const pos = clampTooltipPos(tooltip.x, tooltip.y);
          return (
            <div className="mosaic-photo-tooltip show" style={{ left: pos.left, top: pos.top }}>
              <div className="title">{new Date(tooltip.face.timestamp).toLocaleString()}</div>
              {tooltipInferredName && <div style={{ color: "#fbbf24", fontWeight: 600 }}>Inferred: {tooltipInferredName}</div>}
              {tooltip.miniatureUrl && (
                <div style={{ margin: "6px 0" }}>
                  <img src={tooltip.miniatureUrl} alt="" style={{ maxWidth: 250, maxHeight: 250, borderRadius: 4, display: "block", objectFit: "contain", background: "#000" }} />
                </div>
              )}
              <div className="subtitle">{tooltipHint}</div>
            </div>
          );
        })()}
    </div>
  );
}
