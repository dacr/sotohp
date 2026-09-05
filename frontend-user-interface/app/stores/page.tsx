"use client";

import { useMemo, useState } from "react";
import { Modal } from "../../components/Modal";
import { useOwners } from "../../hooks/useOwners";
import { useCreateStore, useStores, useUpdateStore } from "../../hooks/useStores";
import { showError, showWarning } from "../../lib/toast";
import type { Store } from "../../lib/api-client";

type ModalState = { mode: "create" } | { mode: "edit"; store: Store } | null;

export default function StoresPage() {
  const { data: stores = [], isLoading, refetch } = useStores();
  const { data: owners = [] } = useOwners();
  const createStore = useCreateStore();
  const updateStore = useUpdateStore();
  const [modal, setModal] = useState<ModalState>(null);
  const [name, setName] = useState("");
  const [baseDirectory, setBaseDirectory] = useState("");
  const [ownerName, setOwnerName] = useState("");
  const [includeMask, setIncludeMask] = useState("");
  const [ignoreMask, setIgnoreMask] = useState("");

  const ownersById = useMemo(() => new Map(owners.map((o) => [o.id, `${o.firstName} ${o.lastName}`.trim()])), [owners]);
  const ownerOptions = useMemo(() => owners.map((o) => ({ id: o.id, name: `${o.firstName} ${o.lastName}`.trim() })), [owners]);

  function resolveOwnerId(typed: string): string | null {
    const n = typed.trim();
    if (!n) return null;
    const exact = ownerOptions.find((o) => o.name.toLowerCase() === n.toLowerCase());
    if (exact) return exact.id;
    const candidates = ownerOptions.filter((o) => o.name.toLowerCase().startsWith(n.toLowerCase()));
    return candidates.length === 1 ? candidates[0].id : null;
  }

  function openCreate() {
    setName("");
    setBaseDirectory("");
    setOwnerName("");
    setIncludeMask("");
    setIgnoreMask("");
    setModal({ mode: "create" });
  }
  function openEdit(store: Store) {
    setName(store.name || "");
    setBaseDirectory(store.baseDirectory);
    setIncludeMask(store.includeMask || "");
    setIgnoreMask(store.ignoreMask || "");
    setModal({ mode: "edit", store });
  }

  async function handleSave() {
    const dir = baseDirectory.trim();
    if (!dir) {
      showWarning("Base directory is required");
      return false;
    }
    if (modal?.mode === "edit") {
      try {
        await updateStore.mutateAsync({ id: modal.store.id, body: { name, baseDirectory: dir, includeMask, ignoreMask } });
      } catch {
        showError("Failed to update store");
        return false;
      }
      return;
    }
    const ownerId = resolveOwnerId(ownerName);
    if (!ownerId) {
      showWarning("Please select a valid owner by name");
      return false;
    }
    try {
      await createStore.mutateAsync({ name: name.trim() || undefined, ownerId, baseDirectory: dir, includeMask: includeMask.trim() || undefined, ignoreMask: ignoreMask.trim() || undefined });
    } catch {
      showError("Failed to create store");
      return false;
    }
  }

  return (
    <section className="page">
      <div className="list-actions">
        <button onClick={() => refetch()}>↻ Refresh</button>
        <button onClick={openCreate}>＋ Create store</button>
      </div>
      <ul className="list">
        {isLoading && <li>Loading…</li>}
        {!isLoading && stores.length === 0 && <li>No stores</li>}
        {stores.map((s) => (
          <li key={s.id}>
            <h4 style={{ margin: "0 0 4px 0" }}>
              {s.name ? `${s.name}: ` : ""}
              {s.baseDirectory}
            </h4>
            <div style={{ fontSize: 12, color: "#555" }}>
              id: {s.id} • owner: {ownersById.get(s.ownerId) || s.ownerId}
            </div>
            <button className="ev-edit-btn" title="Edit" onClick={() => openEdit(s)}>
              ✎ Edit
            </button>
          </li>
        ))}
      </ul>

      {modal && (
        <Modal title={modal.mode === "edit" ? "Edit store" : "Create store"} saveLabel={modal.mode === "edit" ? "Save" : "Create"} onClose={() => setModal(null)} onSave={handleSave}>
          <div className="row">
            <div>
              <label>Name</label>
              <input type="text" value={name} onChange={(e) => setName(e.target.value)} autoFocus />
              <label className="form-label">Base directory</label>
              <input type="text" placeholder="/path/to/photos" value={baseDirectory} onChange={(e) => setBaseDirectory(e.target.value)} />
              {modal.mode === "create" && (
                <>
                  <label className="form-label">Owner</label>
                  <input type="text" list="stc-owner-list" placeholder="Type owner name…" autoComplete="off" value={ownerName} onChange={(e) => setOwnerName(e.target.value)} />
                  <datalist id="stc-owner-list">
                    {ownerOptions.map((o) => (
                      <option key={o.id} value={o.name} />
                    ))}
                  </datalist>
                </>
              )}
              <label className="form-label">Include mask</label>
              <input type="text" value={includeMask} onChange={(e) => setIncludeMask(e.target.value)} />
              <label className="form-label">Ignore mask</label>
              <input type="text" value={ignoreMask} onChange={(e) => setIgnoreMask(e.target.value)} />
            </div>
          </div>
        </Modal>
      )}
    </section>
  );
}
