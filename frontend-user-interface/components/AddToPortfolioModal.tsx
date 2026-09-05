"use client";

import { useState } from "react";
import { Modal } from "./Modal";
import { usePortfolios, useAddPortfolioAsset, useCreatePortfolio } from "../hooks/usePortfolios";
import { showError, showSuccess, showWarning } from "../lib/toast";
import type { Media, Portfolio } from "../lib/api-client";

export function AddToPortfolioModal({ media, onClose }: { media: Media; onClose: () => void }) {
  const { data: portfolios = [], isLoading } = usePortfolios();
  const addAsset = useAddPortfolioAsset();
  const createPortfolio = useCreatePortfolio();
  const [description, setDescription] = useState("");
  const [creating, setCreating] = useState(false);
  const [newName, setNewName] = useState("");
  const [newDesc, setNewDesc] = useState("");

  async function addTo(portfolio: Portfolio) {
    const desc = description.trim();
    try {
      await addAsset.mutateAsync({ portfolioId: portfolio.id, asset: { originalId: media.original.id, description: desc || undefined } });
      showSuccess(`Added to "${portfolio.name}"`);
      onClose();
    } catch {
      showError("Failed to add to portfolio");
    }
  }

  async function handleCreateAndAdd() {
    const name = newName.trim();
    if (!name) {
      showWarning("Portfolio name is required");
      return;
    }
    try {
      const created = await createPortfolio.mutateAsync({ name, description: newDesc.trim() || undefined });
      await addTo(created);
    } catch {
      showError("Failed to create portfolio");
    }
  }

  if (creating) {
    return (
      <Modal title="Create new portfolio" saveLabel="Create &amp; Add" onClose={onClose} onSave={handleCreateAndAdd}>
        <label>Name</label>
        <input type="text" value={newName} onChange={(e) => setNewName(e.target.value)} autoFocus />
        <label className="form-label">Description (optional)</label>
        <input type="text" value={newDesc} onChange={(e) => setNewDesc(e.target.value)} />
      </Modal>
    );
  }

  return (
    <Modal title="Add to portfolio" hideSave cancelLabel="Close" onClose={onClose}>
      <label>Description (optional)</label>
      <input type="text" placeholder="A note about this asset…" style={{ width: "100%", marginBottom: 10 }} value={description} onChange={(e) => setDescription(e.target.value)} />
      <label>Select a portfolio</label>
      <ul className="list" style={{ maxHeight: 280, overflowY: "auto", border: "1px solid #e5e7eb", borderRadius: 6, padding: 4, listStyle: "none", margin: "4px 0", gridTemplateColumns: "1fr" }}>
        {isLoading && <li style={{ color: "#9ca3af", padding: 8 }}>Loading…</li>}
        {!isLoading && portfolios.length === 0 && <li style={{ color: "#9ca3af", padding: 8 }}>No portfolio yet — create one below.</li>}
        {portfolios.map((p) => (
          <li key={p.id} style={{ padding: 8, borderBottom: "1px solid #f3f4f6", cursor: "pointer", display: "flex", justifyContent: "space-between", alignItems: "center" }} onClick={() => addTo(p)}>
            <div>
              <div style={{ fontWeight: 600 }}>{p.name || "(no name)"}</div>
              <div style={{ fontSize: 11, color: "#888" }}>{p.assetCount || 0} asset(s)</div>
            </div>
            <span style={{ color: "#2563eb", fontSize: 12 }}>＋ Add</span>
          </li>
        ))}
      </ul>
      <button type="button" className="btn btn-soft" onClick={() => setCreating(true)}>
        ＋ Create new portfolio
      </button>
    </Modal>
  );
}
