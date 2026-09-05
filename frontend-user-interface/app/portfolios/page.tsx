"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useState } from "react";
import { AssetThumb } from "../../components/AssetThumb";
import { CropEditor } from "../../components/CropEditor";
import { Modal } from "../../components/Modal";
import { PortfolioAssetViewer } from "../../components/PortfolioAssetViewer";
import { useInViewport } from "../../hooks/useInViewport";
import { useMediaAccessKey } from "../../hooks/useMediaAccessKey";
import { useAuth } from "../../lib/keycloak-auth";
import {
  useAddPortfolioAsset,
  useCreatePortfolio,
  useDeletePortfolio,
  usePortfolio,
  usePortfolios,
  useRemovePortfolioAsset,
  useUpdatePortfolio,
  useUpdatePortfolioAsset,
} from "../../hooks/usePortfolios";
import { showError, showWarning } from "../../lib/toast";
import type { Asset, BoundingBox, Portfolio } from "../../lib/api-client";

export default function PortfoliosPage() {
  return (
    <Suspense fallback={<section className="page" />}>
      <PortfoliosPageInner />
    </Suspense>
  );
}

function PortfoliosPageInner() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const detailId = searchParams.get("id");
  const [modal, setModal] = useState<"create" | { edit: Portfolio } | null>(null);

  return detailId ? (
    <PortfolioDetail portfolioId={detailId} onBack={() => router.push("/portfolios/")} onEdit={(p) => setModal({ edit: p })} editModal={modal} onCloseModal={() => setModal(null)} />
  ) : (
    <PortfolioList onOpen={(id) => router.push(`/portfolios/?id=${id}`)} modal={modal} onOpenCreate={() => setModal("create")} onCloseModal={() => setModal(null)} />
  );
}

function PortfolioList({ onOpen, modal, onOpenCreate, onCloseModal }: { onOpen: (id: string) => void; modal: "create" | { edit: Portfolio } | null; onOpenCreate: () => void; onCloseModal: () => void }) {
  const { data: portfolios = [], isLoading, refetch } = usePortfolios();
  const createPortfolio = useCreatePortfolio();
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");

  async function handleCreate() {
    const trimmed = name.trim();
    if (!trimmed) {
      showWarning("Portfolio name is required");
      return false;
    }
    try {
      await createPortfolio.mutateAsync({ name: trimmed, description: description.trim() || undefined });
    } catch {
      showError("Failed to create portfolio");
      return false;
    }
  }

  return (
    <section className="page">
      <div className="list-actions">
        <button onClick={() => refetch()}>↻ Refresh</button>
        <button
          onClick={() => {
            setName("");
            setDescription("");
            onOpenCreate();
          }}
        >
          ＋ Create portfolio
        </button>
      </div>
      <ul className="list">
        {isLoading && <li>Loading…</li>}
        {!isLoading && portfolios.length === 0 && <li style={{ color: "#9ca3af", textAlign: "center", padding: 24 }}>No portfolio yet. Click &quot;Create portfolio&quot; to start.</li>}
        {portfolios.map((p) => (
          <li key={p.id} style={{ cursor: "pointer" }} onClick={() => onOpen(p.id)}>
            <div className="list-thumb">
              {(p.assets || []).length === 0 ? (
                <span>{p.assetCount || 0} asset(s)</span>
              ) : (
                <PortfolioMosaicPreview assets={p.assets || []} />
              )}
            </div>
            <h4 style={{ margin: "0 0 4px 0" }}>{p.name || "(no name)"}</h4>
            <div style={{ fontSize: 12, color: "#555" }}>{p.description}</div>
          </li>
        ))}
      </ul>

      {modal === "create" && (
        <Modal title="Create portfolio" saveLabel="Create" onClose={onCloseModal} onSave={handleCreate}>
          <label>Name</label>
          <input type="text" value={name} onChange={(e) => setName(e.target.value)} autoFocus />
          <label className="form-label">Description (optional)</label>
          <input type="text" value={description} onChange={(e) => setDescription(e.target.value)} />
        </Modal>
      )}
    </section>
  );
}

function PortfolioMosaicPreview({ assets }: { assets: Asset[] }) {
  const preview = assets.slice(0, 4);
  const cols = preview.length === 1 ? "1fr" : "1fr 1fr";
  const rows = preview.length <= 2 ? "1fr" : "1fr 1fr";
  return (
    <div style={{ position: "absolute", inset: 0, display: "grid", gap: 1, background: "#e5e7eb", gridTemplateColumns: cols, gridTemplateRows: rows }}>
      {preview.map((asset, i) => (
        <div key={i} style={{ background: "#f3f4f6", overflow: "hidden", position: "relative", gridColumn: preview.length === 3 && i === 0 ? "1 / span 2" : undefined }}>
          <AssetThumb asset={asset} />
        </div>
      ))}
    </div>
  );
}

function PortfolioDetail({
  portfolioId,
  onBack,
  onEdit,
  editModal,
  onCloseModal,
}: {
  portfolioId: string;
  onBack: () => void;
  onEdit: (p: Portfolio) => void;
  editModal: "create" | { edit: Portfolio } | null;
  onCloseModal: () => void;
}) {
  const { data: portfolio, refetch } = usePortfolio(portfolioId);
  const deletePortfolio = useDeletePortfolio();
  const removeAsset = useRemovePortfolioAsset();
  const updatePortfolio = useUpdatePortfolio();
  const { api } = useAuth();
  const [viewerIndex, setViewerIndex] = useState<number | null>(null);
  const [editingAsset, setEditingAsset] = useState<Asset | null>(null);
  const [editName, setEditName] = useState("");
  const [editDesc, setEditDesc] = useState("");

  if (!portfolio) return <section className="page" />;
  const assets = portfolio.assets || [];

  async function handleDelete() {
    if (!confirm(`Delete portfolio "${portfolio!.name}" and all its assets?`)) return;
    try {
      await deletePortfolio.mutateAsync(portfolio!.id);
      onBack();
    } catch {
      showError("Failed to delete portfolio");
    }
  }

  async function handleEditSave() {
    const trimmed = editName.trim();
    if (!trimmed) {
      showWarning("Portfolio name is required");
      return false;
    }
    try {
      await updatePortfolio.mutateAsync({ id: portfolio!.id, body: { name: trimmed, description: editDesc.trim() || undefined } });
    } catch {
      showError("Failed to update portfolio");
      return false;
    }
  }

  return (
    <section className="page">
      <div className="list-actions">
        <button onClick={onBack}>← Back</button>
        <span style={{ fontWeight: 600, marginLeft: 8 }}>{portfolio.name}</span>
        <span style={{ flex: 1 }} />
        <button onClick={() => refetch()}>↻ Refresh</button>
        <button
          disabled={assets.length === 0}
          onClick={() => {
            if (assets.length === 0) {
              showWarning("No asset in this portfolio");
              return;
            }
            setViewerIndex(0);
          }}
        >
          👁 View assets
        </button>
        <button
          onClick={() => {
            setEditName(portfolio.name || "");
            setEditDesc(portfolio.description || "");
            onEdit(portfolio);
          }}
        >
          ✎ Edit
        </button>
        <button style={{ background: "#ef4444", color: "#fff", border: "none", padding: "4px 12px", borderRadius: 4, cursor: "pointer" }} onClick={handleDelete}>
          🗑 Delete
        </button>
      </div>
      {portfolio.description && <p className="muted" style={{ margin: "8px 4px" }}>{portfolio.description}</p>}
      <ul className="list">
        {assets.length === 0 && <li style={{ color: "#9ca3af", textAlign: "center", padding: 24 }}>No asset yet. Open a photo in the Viewer and use &quot;Add to portfolio…&quot; to add one.</li>}
        {assets.map((asset, i) => (
          <PortfolioAssetTile
            key={`${asset.originalId}-${i}`}
            asset={asset}
            onView={() => setViewerIndex(i)}
            onEdit={() => setEditingAsset(asset)}
            onRemove={async () => {
              if (!confirm("Remove this asset from the portfolio?")) return;
              try {
                await removeAsset.mutateAsync({ portfolioId: portfolio.id, asset });
              } catch {
                showError("Failed to remove asset");
              }
            }}
          />
        ))}
      </ul>

      {editModal && typeof editModal === "object" && (
        <Modal title="Edit portfolio" onClose={onCloseModal} onSave={handleEditSave}>
          <label>Name</label>
          <input type="text" value={editName} onChange={(e) => setEditName(e.target.value)} autoFocus />
          <label className="form-label">Description (optional)</label>
          <input type="text" value={editDesc} onChange={(e) => setEditDesc(e.target.value)} />
        </Modal>
      )}

      {viewerIndex !== null && <PortfolioAssetViewer assets={assets} startIndex={viewerIndex} onClose={() => setViewerIndex(null)} />}

      {editingAsset && <AssetEditModal portfolioId={portfolio.id} asset={editingAsset} onClose={() => setEditingAsset(null)} />}
    </section>
  );
}

function PortfolioAssetTile({ asset, onView, onEdit, onRemove }: { asset: Asset; onView: () => void; onEdit: () => void; onRemove: () => void }) {
  const [ref, inView] = useInViewport<HTMLLIElement>();
  const accessKey = useMediaAccessKey(inView ? asset.originalId : null);

  return (
    <li
      ref={ref}
      style={accessKey ? { cursor: "pointer" } : undefined}
      onClick={(e) => {
        if ((e.target as HTMLElement).closest("button")) return;
        if (accessKey) onView();
      }}
    >
      <div className="list-thumb">
        {!accessKey && <span>Loading…</span>}
        {accessKey && <AssetThumb asset={asset} resolveRotation />}
        {asset.selectedBox && <span className="icon-badge">✂ Cropped</span>}
      </div>
      <div style={{ display: "flex", alignItems: "center", gap: 6, fontSize: 12, margin: "4px 0 2px 0" }}>
        <div style={{ flex: 1, minWidth: 0, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
          {asset.description ? <span style={{ color: "#374151", fontStyle: "italic" }}>{asset.description}</span> : <span style={{ color: "#9ca3af", fontStyle: "italic" }}>No description</span>}
        </div>
        <button className="pf-asset-icon pf-asset-view" title="Open in viewer tab" aria-label="Open in viewer tab" disabled={!accessKey} onClick={onView}>
          👁
        </button>
        <button className="pf-asset-icon pf-asset-edit" title="Edit description" aria-label="Edit description" onClick={onEdit}>
          ✎
        </button>
        <button className="pf-asset-icon pf-asset-remove" title="Remove from portfolio" aria-label="Remove from portfolio" onClick={onRemove}>
          🗑
        </button>
      </div>
    </li>
  );
}

function AssetEditModal({ portfolioId, asset, onClose }: { portfolioId: string; asset: Asset; onClose: () => void }) {
  const { api } = useAuth();
  const accessKey = useMediaAccessKey(asset.originalId);
  const updateAsset = useUpdatePortfolioAsset();
  const [description, setDescription] = useState(asset.description || "");
  const [cropBox, setCropBox] = useState<BoundingBox | null>(asset.selectedBox || null);

  async function handleSave() {
    const oldAsset: Asset = { originalId: asset.originalId, selectedBox: asset.selectedBox, description: asset.description };
    const newAsset: Asset = { originalId: asset.originalId, selectedBox: cropBox || undefined, description: description.trim() || undefined };
    try {
      await updateAsset.mutateAsync({ portfolioId, oldAsset, newAsset });
    } catch {
      showError("Failed to update asset");
      return false;
    }
  }

  return (
    <Modal title="Edit asset" onClose={onClose} onSave={handleSave} widthCss="min(760px, 90vw)">
      <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
        <div>
          <label>Description (optional)</label>
          <input type="text" placeholder="A note about this asset…" value={description} onChange={(e) => setDescription(e.target.value)} autoFocus />
        </div>
        <div>
          <label>Crop region (drag to move, drag corners to resize)</label>
          {accessKey ? <CropEditor imgSrc={api.mediaNormalizedUrl(accessKey)} box={cropBox} onChange={setCropBox} /> : <div style={{ color: "#9ca3af", fontSize: 13 }}>Loading image…</div>}
        </div>
      </div>
    </Modal>
  );
}
