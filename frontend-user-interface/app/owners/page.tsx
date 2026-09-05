"use client";

import { useState } from "react";
import { Modal } from "../../components/Modal";
import { LazyThumb } from "../../components/LazyThumb";
import { useCreateOwner, useOwners, useUpdateOwner } from "../../hooks/useOwners";
import { showError, showWarning } from "../../lib/toast";
import type { Owner } from "../../lib/api-client";

type ModalState = { mode: "create" } | { mode: "edit"; owner: Owner } | null;

function toDateInput(iso: string | undefined | null): string {
  if (!iso) return "";
  const d = new Date(iso);
  if (isNaN(d.getTime())) return "";
  return d.toISOString().slice(0, 10);
}

export default function OwnersPage() {
  const { data: owners = [], isLoading, refetch } = useOwners();
  const createOwner = useCreateOwner();
  const updateOwner = useUpdateOwner();
  const [modal, setModal] = useState<ModalState>(null);
  const [firstName, setFirstName] = useState("");
  const [lastName, setLastName] = useState("");
  const [birthDate, setBirthDate] = useState("");

  function openCreate() {
    setFirstName("");
    setLastName("");
    setBirthDate("");
    setModal({ mode: "create" });
  }
  function openEdit(owner: Owner) {
    setFirstName(owner.firstName);
    setLastName(owner.lastName);
    setBirthDate(toDateInput(owner.birthDate));
    setModal({ mode: "edit", owner });
  }

  async function handleSave() {
    const first = firstName.trim();
    const last = lastName.trim();
    if (!first || !last) {
      showWarning("First name and Last name are required");
      return false;
    }
    const body = { firstName: first, lastName: last, birthDate: birthDate ? `${birthDate}T00:00:00Z` : undefined };
    try {
      if (modal?.mode === "edit") await updateOwner.mutateAsync({ id: modal.owner.id, body });
      else await createOwner.mutateAsync(body);
    } catch {
      showError(modal?.mode === "edit" ? "Failed to update owner" : "Failed to create owner");
      return false;
    }
  }

  return (
    <section className="page">
      <div className="list-actions">
        <button onClick={() => refetch()}>↻ Refresh</button>
        <button onClick={openCreate}>＋ Create owner</button>
      </div>
      <ul className="list">
        {isLoading && <li>Loading…</li>}
        {!isLoading && owners.length === 0 && <li>No owners</li>}
        {owners.map((o) => (
          <li key={o.id}>
            <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
              <LazyThumb originalId={o.originalId} alt={`${o.firstName} ${o.lastName}`} small />
              <div style={{ flex: 1 }}>
                <h4 style={{ margin: "0 0 4px 0" }}>
                  {o.firstName} {o.lastName}
                </h4>
                <div style={{ fontSize: 12, color: "#555" }}>
                  id: {o.id}
                  {o.birthDate ? ` • birth: ${new Date(o.birthDate).toLocaleDateString()}` : ""}
                </div>
              </div>
              <button className="ev-edit-btn" title="Edit" onClick={() => openEdit(o)}>
                ✎ Edit
              </button>
            </div>
          </li>
        ))}
      </ul>

      {modal && (
        <Modal title={modal.mode === "edit" ? "Edit owner" : "Create owner"} saveLabel={modal.mode === "edit" ? "Save" : "Create"} onClose={() => setModal(null)} onSave={handleSave}>
          <div className="row">
            <div>
              <label>First name</label>
              <input type="text" value={firstName} onChange={(e) => setFirstName(e.target.value)} autoFocus />
              <label className="form-label">Last name</label>
              <input type="text" value={lastName} onChange={(e) => setLastName(e.target.value)} />
              <label className="form-label">Birthdate</label>
              <input type="date" value={birthDate} onChange={(e) => setBirthDate(e.target.value)} />
            </div>
          </div>
        </Modal>
      )}
    </section>
  );
}
