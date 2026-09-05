"use client";

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

export function FacesOverlay({
  rect,
  faces,
  personsMap,
  onConfirmInferred,
  onEdit,
}: {
  rect: ImageRect;
  faces: DetectedFace[];
  personsMap: Map<string, Person>;
  onConfirmInferred: (face: DetectedFace) => void;
  onEdit: (face: DetectedFace) => void;
}) {
  if (rect.width <= 0 || rect.height <= 0) return null;

  return (
    <div className="faces-overlay">
      {faces.map((face) => {
        const b = face.box;
        const left = rect.left + Math.round(b.x * rect.width);
        const top = rect.top + Math.round(b.y * rect.height);
        const w = Math.round(b.width * rect.width);
        const h = Math.round(b.height * rect.height);
        const pid = face.identifiedPersonId || null;
        const inferredPid = !pid && !face.inferredIgnore ? face.inferredIdentifiedPersonId || null : null;
        const identified = pid ? personLabel(personsMap.get(pid)) : null;
        const inferred = inferredPid ? personLabel(personsMap.get(inferredPid)) : null;

        return (
          <div key={face.faceId} className="face-box" style={{ left, top, width: w, height: h }} title={identified?.full || (inferred?.full ? `${inferred.full} (inferred)` : undefined)}>
            {identified?.first && <div className="name-chip">{identified.first}</div>}
            {!identified?.first && inferred?.first && (
              <button type="button" className="name-chip inferred" title={inferred.full ? `${inferred.full} (click to confirm)` : "Click to confirm"} onClick={() => onConfirmInferred(face)}>
                {inferred.first}?
              </button>
            )}
            <button type="button" className="fb-edit" title="Edit identification" onClick={() => onEdit(face)}>
              ✎
            </button>
          </div>
        );
      })}
    </div>
  );
}
