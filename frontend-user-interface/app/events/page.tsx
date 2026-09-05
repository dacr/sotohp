"use client";

import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { KeywordChips } from "../../components/KeywordChips";
import { LazyThumb } from "../../components/LazyThumb";
import { LocationPicker } from "../../components/LocationPicker";
import { LocationPin } from "../../components/LocationPin";
import { Modal } from "../../components/Modal";
import { useBags, useUpdateBag } from "../../hooks/useBags";
import { toLocalInputValue, fromLocalInputValue } from "../../lib/datetime";
import { showError, showWarning } from "../../lib/toast";
import type { Bag, Location } from "../../lib/api-client";

const SCROLL_KEY = "events.scrollTop";

export default function EventsPage() {
  const { data: bags = [], isLoading, refetch } = useBags();
  const updateBag = useUpdateBag();
  const [editing, setEditing] = useState<Bag | null>(null);
  const router = useRouter();
  const sectionRef = useRef<HTMLElement>(null);

  // Persist/restore scroll position across navigation — each route unmounts now (no more
  // show/hide tab divs staying alive in the DOM), so this matters more than it used to.
  useEffect(() => {
    const sec = sectionRef.current;
    if (!sec) return;
    try {
      const saved = parseInt(localStorage.getItem(SCROLL_KEY) || "0", 10);
      if (saved > 0) sec.scrollTop = saved;
    } catch {
      /* ignore */
    }
    let timer: ReturnType<typeof setTimeout> | null = null;
    const onScroll = () => {
      if (timer) clearTimeout(timer);
      timer = setTimeout(() => {
        try {
          localStorage.setItem(SCROLL_KEY, String(Math.max(0, sec.scrollTop | 0)));
        } catch {
          /* ignore */
        }
      }, 120);
    };
    sec.addEventListener("scroll", onScroll, { passive: true });
    return () => sec.removeEventListener("scroll", onScroll);
  }, [bags.length]);

  function goToMosaicAtTimestamp(ts: string | undefined) {
    if (!ts) return;
    router.push(`/mosaic/?ts=${encodeURIComponent(ts)}`);
  }

  return (
    <section className="page" ref={sectionRef} tabIndex={0} aria-label="Bags">
      <div className="list-actions">
        <button onClick={() => refetch()}>↻ Refresh</button>
      </div>
      <ul className="list">
        {isLoading && <li>Loading…</li>}
        {!isLoading && bags.length === 0 && <li>No bags</li>}
        {bags.map((bag) => (
          <li
            key={bag.id}
            style={bag.originalId ? { cursor: "pointer" } : undefined}
            onClick={bag.originalId ? () => goToMosaicAtTimestamp(bag.timestamp) : undefined}
          >
            <div className="ev-thumb">
              <LazyThumb originalId={bag.originalId} alt={bag.name || ""} variant="normalized" />
            </div>
            <h4 style={{ margin: "0 0 4px 0" }}>{bag.name || "(no name)"}</h4>
            <div style={{ fontSize: 12, color: "#555" }}>
              {bag.location && <LocationPin />}
              {bag.timestamp ? new Date(bag.timestamp).toLocaleString() : ""}
            </div>
            <button
              className="ev-edit-btn"
              title="Edit"
              onClick={(e) => {
                e.stopPropagation();
                setEditing(bag);
              }}
            >
              ✎ Edit
            </button>
          </li>
        ))}
      </ul>

      {editing && (
        <BagEditModal
          bag={editing}
          onClose={() => setEditing(null)}
          onSave={async (body) => {
            try {
              await updateBag.mutateAsync({ id: editing.id, body });
            } catch {
              showError("Failed to save bag");
              return false;
            }
          }}
        />
      )}
    </section>
  );
}

function BagEditModal({ bag, onClose, onSave }: { bag: Bag; onClose: () => void; onSave: (body: import("../../lib/api-client").BagUpdate) => Promise<boolean | void> }) {
  const [name, setName] = useState(bag.name || "");
  const [description, setDescription] = useState(bag.description || "");
  const [timestamp, setTimestamp] = useState(toLocalInputValue(bag.timestamp));
  const [publishedOn, setPublishedOn] = useState(bag.publishedOn || "");
  const [keywords, setKeywords] = useState<string[]>(bag.keywords || []);
  const [location, setLocation] = useState<Location | null>(bag.location || null);

  async function handleSave() {
    const trimmedName = name.trim();
    if (!trimmedName) {
      showWarning("Name is required");
      return false;
    }
    let publishedUrl: string | undefined;
    const published = publishedOn.trim();
    if (published) {
      try {
        new URL(published);
        publishedUrl = published;
      } catch {
        showWarning("Invalid Published On URL");
        return false;
      }
    }
    return onSave({
      name: trimmedName,
      description: description.trim() || undefined,
      timestamp: fromLocalInputValue(timestamp),
      publishedOn: published === "" ? undefined : publishedUrl,
      location: location || undefined,
      keywords: keywords.length > 0 ? keywords : undefined,
    });
  }

  return (
    <Modal title="Edit bag" onClose={onClose} onSave={handleSave} widthCss="min(900px, 96vw)">
      <div className="row">
        <div>
          <label>Name</label>
          <input type="text" value={name} onChange={(e) => setName(e.target.value)} autoFocus />
          <label className="form-label">Description</label>
          <textarea value={description} onChange={(e) => setDescription(e.target.value)} />
          <label className="form-label">Timestamp</label>
          <input type="datetime-local" value={timestamp} onChange={(e) => setTimestamp(e.target.value)} />
          <label className="form-label">Published On (URL)</label>
          <input type="url" placeholder="https://example.com/album" value={publishedOn} onChange={(e) => setPublishedOn(e.target.value)} />
          <label className="form-label">Keywords</label>
          <KeywordChips value={keywords} onChange={setKeywords} />
        </div>
        <div>
          <label>Location</label>
          <LocationPicker value={location} onChange={setLocation} />
        </div>
      </div>
    </Modal>
  );
}
