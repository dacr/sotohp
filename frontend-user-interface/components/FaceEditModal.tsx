"use client";

// Identify/re-identify/remove/delete a face, shared by the persons face grids and the viewer's
// faces overlay. Uses a native <input list=...> combobox instead of a hand-rolled ARIA combobox
// with manual arrow-key navigation — the browser already does typeahead filtering for us (same
// pattern as the store-owner picker on the Stores page).
import { useEffect, useMemo, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { Modal } from "./Modal";
import { usePersonsMap } from "../hooks/usePersons";
import { useDeleteFace, useRemoveFacePerson, useSetFacePerson } from "../hooks/useFaces";
import { useAuth } from "../lib/keycloak-auth";
import { getRecentPersonIds, pushRecentPersonId } from "../lib/recent-persons";
import { showError, showSuccess, showWarning } from "../lib/toast";
import type { DetectedFace, Person } from "../lib/api-client";

function personLabel(p: Person): string {
  return `${p.firstName || ""} ${p.lastName || ""}`.trim() || p.id;
}

export function FaceEditModal({
  face,
  onClose,
  onChanged,
  onDeleted,
}: {
  face: DetectedFace;
  onClose: () => void;
  onChanged?: (updated: DetectedFace) => void;
  onDeleted?: (faceId: string) => void;
}) {
  const { api } = useAuth();
  const qc = useQueryClient();
  const personsMap = usePersonsMap();
  const persons = useMemo(() => Array.from(personsMap.values()), [personsMap]);
  const setFacePerson = useSetFacePerson();
  const removeFacePerson = useRemoveFacePerson();
  const deleteFace = useDeleteFace();

  const currentPerson = face.identifiedPersonId ? personsMap.get(face.identifiedPersonId) : undefined;
  const [nameInput, setNameInput] = useState(currentPerson ? personLabel(currentPerson) : "");
  const [recentIds, setRecentIds] = useState<string[]>([]);
  useEffect(() => setRecentIds(getRecentPersonIds()), []);

  const selectedId = useMemo(() => {
    const n = nameInput.trim().toLowerCase();
    if (!n) return null;
    const exact = persons.find((p) => personLabel(p).toLowerCase() === n);
    return exact ? exact.id : null;
  }, [nameInput, persons]);

  async function handleSave() {
    if (!selectedId) {
      showWarning("Please select a person");
      return false;
    }
    try {
      await setFacePerson.mutateAsync({ faceId: face.faceId, personId: selectedId });
      pushRecentPersonId(selectedId);
      showSuccess("Face identification updated");
      onChanged?.({ ...face, identifiedPersonId: selectedId, inferredIdentifiedPersonId: undefined });
    } catch {
      showError("Failed to update face identification");
      return false;
    }
  }

  async function handleRemove() {
    if (!face.identifiedPersonId) return;
    try {
      await removeFacePerson.mutateAsync(face.faceId);
      showSuccess("Face identification removed");
      onChanged?.({ ...face, identifiedPersonId: undefined });
      onClose();
    } catch {
      showError("Failed to remove face identification");
    }
  }

  async function handleUseAsChosen() {
    if (!selectedId) {
      showWarning("Please select a person first");
      return;
    }
    try {
      await api.updatePersonFace(selectedId, face.faceId);
      qc.invalidateQueries({ queryKey: ["persons"] });
      pushRecentPersonId(selectedId);
      showSuccess("Set as chosen face for the selected person");
    } catch {
      showError("Failed to set chosen face");
    }
  }

  async function handleDelete() {
    try {
      await deleteFace.mutateAsync(face.faceId);
      showSuccess("Face deleted");
      onDeleted?.(face.faceId);
      onClose();
    } catch {
      showError("Failed to delete face");
    }
  }

  async function quickPick(personId: string) {
    try {
      await setFacePerson.mutateAsync({ faceId: face.faceId, personId });
      pushRecentPersonId(personId);
      showSuccess("Face identification saved");
      onChanged?.({ ...face, identifiedPersonId: personId, inferredIdentifiedPersonId: undefined });
      onClose();
    } catch {
      showError("Failed to save identification");
    }
  }

  const recentPersons = recentIds
    .map((id) => personsMap.get(id))
    .filter((p): p is Person => !!p)
    .slice(0, 10);

  return (
    <Modal
      title="Identify person for face"
      onClose={onClose}
      onSave={handleSave}
      saveDisabled={!selectedId}
      footerExtra={
        <>
          <button type="button" className="danger" onClick={handleDelete}>
            Delete face
          </button>
          <button type="button" className="btn btn-success" disabled={!selectedId} onClick={handleUseAsChosen}>
            Use as chosen face
          </button>
          <button type="button" className="btn btn-danger-soft" disabled={!face.identifiedPersonId} onClick={handleRemove}>
            Remove
          </button>
        </>
      }
    >
      <div className="row">
        <div>
          <label htmlFor="fp-person-input">Person</label>
          <input
            id="fp-person-input"
            type="text"
            list="fp-person-list"
            autoComplete="off"
            placeholder="Type a name to filter…"
            value={nameInput}
            onChange={(e) => setNameInput(e.target.value)}
            style={{ width: "100%" }}
            autoFocus
          />
          <datalist id="fp-person-list">
            {persons.map((p) => (
              <option key={p.id} value={personLabel(p)} />
            ))}
          </datalist>
          <p className="muted-sm" style={{ marginTop: 6 }}>
            Pick a person to set/update identification, or use Remove to clear it.
          </p>
        </div>
        {recentPersons.length > 0 && (
          <div className="recent-persons">
            <label>Recent</label>
            <div className="recent-list">
              {recentPersons.map((p) => (
                <button key={p.id} type="button" className="recent-pill" title={`Quick select ${personLabel(p)}`} onClick={() => quickPick(p.id)}>
                  {personLabel(p)}
                </button>
              ))}
            </div>
            <p className="muted-sm" style={{ marginTop: 6 }}>
              Quick select one of your last choices.
            </p>
          </div>
        )}
      </div>
    </Modal>
  );
}
