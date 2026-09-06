"use client";

// Shared face-tile grid for both the per-person faces view (identified / to-validate modes) and
// the cross-person "all inferred faces" review queue. Click-to-select + shift-click range select
// replaces the original's continuous drag-select — functionally equivalent for the common case,
// far less event-wiring to get right.
import { useAuth } from "../lib/keycloak-auth";
import { faceBoxVersion, type DetectedFace, type Person } from "../lib/api-client";

export function FaceGrid({
  faces,
  mode,
  scopePersonId,
  personsMap,
  selected,
  onToggleSelect,
  onConfirmInferred,
  onIgnore,
  onRestore,
  onEdit,
  onOpenViewer,
}: {
  faces: DetectedFace[];
  mode: "identified" | "validate";
  scopePersonId: string | null; // null = cross-person "all inferred" view
  personsMap: Map<string, Person>;
  selected: Set<string>;
  onToggleSelect: (faceId: string, index: number, shiftKey: boolean) => void;
  onConfirmInferred: (face: DetectedFace) => void;
  onIgnore: (face: DetectedFace) => void;
  onRestore: (face: DetectedFace) => void;
  onEdit: (face: DetectedFace) => void;
  onOpenViewer: (face: DetectedFace) => void;
}) {
  const { api } = useAuth();

  if (faces.length === 0) {
    const msg = mode === "validate" && !scopePersonId ? "No inferred faces found." : "No faces found for this person";
    return <div className="status muted">{msg}</div>;
  }

  function personName(id: string | undefined | null): string {
    if (!id) return "";
    const p = personsMap.get(id);
    return p ? `${p.firstName || ""} ${p.lastName || ""}`.trim() : "";
  }

  return (
    <div className="person-faces-grid">
      {faces.map((face, index) => {
        const isSelected = selected.has(face.faceId);
        const isInferredForScope = !face.identifiedPersonId && (scopePersonId ? face.inferredIdentifiedPersonId === scopePersonId : !!face.inferredIdentifiedPersonId);
        return (
          <div
            key={face.faceId}
            className={`face-tile${face.inferredIgnore ? " face-ignored" : ""}`}
            style={mode === "validate" ? { cursor: "crosshair", outline: isSelected ? "3px solid #2563eb" : undefined } : { cursor: "pointer" }}
            tabIndex={0}
            onClick={(e) => {
              if ((e.target as HTMLElement).closest("button")) return;
              if (mode === "validate") onToggleSelect(face.faceId, index, e.shiftKey);
              else onOpenViewer(face);
            }}
            title={mode === "validate" ? "Click to select · Shift-click for a range" : "Click to open in Viewer"}
          >
            <img className="face-img" src={api.faceImageUrl(face.faceId, faceBoxVersion(face))} alt="face" loading="lazy" decoding="async" draggable={false} />
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
    </div>
  );
}
