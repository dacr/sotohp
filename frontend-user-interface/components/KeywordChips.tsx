"use client";

import { useState, type KeyboardEvent } from "react";

// Colored removable keyword chips, shared between the media-edit and event-edit forms.
export function KeywordChips({ value, onChange }: { value: string[]; onChange: (next: string[]) => void }) {
  const [draft, setDraft] = useState("");

  function commitDraft() {
    const kw = draft.trim();
    if (kw && !value.includes(kw)) onChange([...value, kw]);
    setDraft("");
  }

  function handleKeyDown(e: KeyboardEvent<HTMLInputElement>) {
    if (e.key === "Enter" || e.key === ",") {
      e.preventDefault();
      commitDraft();
    } else if (e.key === "Backspace" && draft === "" && value.length > 0) {
      onChange(value.slice(0, -1));
    }
  }

  return (
    <div className="chips">
      {value.map((kw) => (
        <span className="chip" key={kw}>
          {kw}
          <button type="button" className="remove" onClick={() => onChange(value.filter((k) => k !== kw))}>
            ×
          </button>
        </span>
      ))}
      <input type="text" placeholder="Add keyword and press Enter" value={draft} onChange={(e) => setDraft(e.target.value)} onKeyDown={handleKeyDown} onBlur={commitDraft} />
    </div>
  );
}
