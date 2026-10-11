"use client";

// Shared face-tile grid for both the per-person faces view (identified / to-validate modes) and
// the cross-person "all inferred faces" review queue.
import { useRef, type Dispatch, type SetStateAction } from "react";
import { useFaceImageVersion } from "../hooks/useFaces";
import { useAuth } from "../lib/keycloak-auth";
import { FacePhotoPreview, useFacePhotoPreview } from "./FacePhotoPreview";
import type { DetectedFace, Person } from "../lib/api-client";

export interface ImageRect {
  left: number;
  top: number;
  width: number;
  height: number;
}

function personLabel(p: Person | undefined): { first: string; full: string } {
  if (!p) return { first: "", full: "" };
  const first = (p.firstName || "").trim();
  const last = (p.lastName || "").trim();
  return { first, full: `${first}${last ? " " + last : ""}` };
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
  const faceImageVersion = useFaceImageVersion();

  // Drag-to-select: mousedown on a tile starts a paint gesture (add or remove, decided by that
  // first tile's new state, or by a shift-click range) that mouseenter on subsequent tiles
  // continues until mouseup - lets you sweep the mouse across many faces instead of click/shift-
  // clicking each one.
  const dragIntentRef = useRef<boolean | null>(null);
  const lastIndexRef = useRef<number>(-1);
  const photoPreview = useFacePhotoPreview();


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

  const previewFace = photoPreview.preview?.face;
  const previewInferredName = previewFace && !scopePersonId ? personName(previewFace.inferredIdentifiedPersonId) : "";
  const previewHint = mode === "validate" ? "Click to select · Shift-click range · Drag to multi-select" : "Click to open in Viewer";

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
              src={api.faceImageUrl(face.faceId, faceImageVersion(face))}
              alt="face"
              loading="lazy"
              decoding="async"
              draggable={false}
              onMouseEnter={(e) => photoPreview.onMouseEnter(e, face)}
              onMouseMove={(e) => photoPreview.onMouseMove(e, face)}
              onMouseLeave={photoPreview.onMouseLeave}
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
      <FacePhotoPreview
        preview={photoPreview.preview}
        hint={previewHint}
        details={previewInferredName && <div style={{ color: "#fbbf24", fontWeight: 600 }}>Inferred: {previewInferredName}</div>}
      />
    </div>
  );
}
