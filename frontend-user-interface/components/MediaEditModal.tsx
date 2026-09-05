"use client";

import { useState } from "react";
import { KeywordChips } from "./KeywordChips";
import { LocationPicker } from "./LocationPicker";
import { Modal } from "./Modal";
import { AddToPortfolioModal } from "./AddToPortfolioModal";
import { useAuth } from "../lib/keycloak-auth";
import { fromLocalInputValue, toLocalInputValue } from "../lib/datetime";
import { showError, showSuccess, showWarning } from "../lib/toast";
import type { Location, Media } from "../lib/api-client";

export function MediaEditModal({ media, onClose, onSaved }: { media: Media; onClose: () => void; onSaved: (updated: Media) => void }) {
  const { api } = useAuth();
  const [description, setDescription] = useState(media.description || "");
  const [timestamp, setTimestamp] = useState(toLocalInputValue(media.shootDateTime || media.original.cameraShootDateTime));
  const [keywords, setKeywords] = useState<string[]>(media.keywords || []);
  const [location, setLocation] = useState<Location | null>(media.userDefinedLocation || null);
  const [showAddToPortfolio, setShowAddToPortfolio] = useState(false);

  async function handleSetBagCover() {
    if (!media.bag) {
      showWarning("This media is not associated with any bag");
      return;
    }
    try {
      await api.setBagCover(media.bag.id, media.accessKey);
      showSuccess("Successfully set as bag cover");
    } catch {
      showError("Failed to set as bag cover");
    }
  }

  async function handleSetOwnerCover() {
    if (!media.original.storeId) {
      showWarning("This media is not associated with any store");
      return;
    }
    try {
      const store = await api.getStore(media.original.storeId);
      if (!store.ownerId) {
        showWarning("This media is not associated with any owner");
        return;
      }
      await api.setOwnerCover(store.ownerId, media.accessKey);
      showSuccess("Successfully set as owner cover");
    } catch {
      showError("Failed to set as owner cover");
    }
  }

  async function handleSave() {
    const body = {
      starred: !!media.starred,
      description: description.trim() || undefined,
      shootDateTime: fromLocalInputValue(timestamp),
      keywords,
      orientation: media.orientation,
      userDefinedLocation: location || undefined,
    };
    try {
      await api.updateMedia(media.accessKey, body);
      const updated = await api.getMediaByKey(media.accessKey);
      onSaved(updated);
    } catch {
      showError("Failed to save media");
      return false;
    }
  }

  return (
    <>
      <Modal
        title="Edit media"
        onClose={onClose}
        onSave={handleSave}
        headerExtra={
          <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
            <button type="button" className="btn btn-primary btn-sm" onClick={handleSetBagCover}>
              Use for bag cover
            </button>
            <button type="button" className="btn btn-primary btn-sm" onClick={handleSetOwnerCover}>
              Use for owner cover
            </button>
            <button type="button" className="btn btn-success btn-sm" onClick={() => setShowAddToPortfolio(true)}>
              ＋ Add to portfolio…
            </button>
          </div>
        }
      >
        <div className="row">
          <div>
            <label>Description</label>
            <input type="text" value={description} onChange={(e) => setDescription(e.target.value)} autoFocus />
            <label className="form-label">Shoot date/time</label>
            <input type="datetime-local" value={timestamp} onChange={(e) => setTimestamp(e.target.value)} />
            <label className="form-label">Keywords</label>
            <KeywordChips value={keywords} onChange={setKeywords} />
          </div>
          <div>
            <label>User-defined location</label>
            <LocationPicker value={location} onChange={setLocation} mediaLocation={media.original.location} fallbackLocation={media.bag?.location} />
          </div>
        </div>
      </Modal>
      {showAddToPortfolio && <AddToPortfolioModal media={media} onClose={() => setShowAddToPortfolio(false)} />}
    </>
  );
}
