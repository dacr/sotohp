"use client";

// Shared modal scaffold (overlay + header + content + footer), replacing the repeated
// `open*Modal` boilerplate every create/edit form used in the previous app (lib/modal.js's
// `openModal`). Only one modal is ever open at a time in practice — each page keeps a single
// `modal` piece of state (e.g. `{ mode: "create" } | { mode: "edit"; item } | null`) and renders
// at most one <Modal>, so no extra "single instance" guard is needed here (React already only
// mounts what a page renders).
import { useEffect, useRef, useState, type ReactNode } from "react";

export interface ModalProps {
  title: string;
  children: ReactNode;
  onClose: () => void;
  onSave?: () => Promise<boolean | void> | boolean | void; // return false to keep the modal open (validation failure)
  saveLabel?: string;
  saveDisabled?: boolean;
  cancelLabel?: string | null; // null hides the Cancel button
  hideSave?: boolean;
  headerExtra?: ReactNode; // extra action buttons in the header, before the close button
  footerExtra?: ReactNode; // extra footer buttons (e.g. a destructive "Remove"), before Cancel/Save
  widthCss?: string;
}

export function Modal({
  title,
  children,
  onClose,
  onSave,
  saveLabel = "Save",
  saveDisabled = false,
  cancelLabel = "Cancel",
  hideSave = false,
  headerExtra,
  footerExtra,
  widthCss,
}: ModalProps) {
  const modalRef = useRef<HTMLDivElement>(null);
  const [saving, setSaving] = useState(false);

  async function handleSave() {
    if (!onSave || saving) return;
    setSaving(true);
    try {
      const result = await onSave();
      if (result !== false) onClose();
    } finally {
      setSaving(false);
    }
  }

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") onClose();
      else if ((e.ctrlKey || e.metaKey) && e.key === "Enter") handleSave();
    }
    document.addEventListener("keydown", onKey);
    modalRef.current?.focus({ preventScroll: true });
    return () => document.removeEventListener("keydown", onKey);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <div
      className="modal-overlay"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div className="modal" role="dialog" aria-modal="true" tabIndex={-1} ref={modalRef} style={widthCss ? { width: widthCss } : undefined}>
        <header>
          <div>{title}</div>
          <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
            {headerExtra}
            <button type="button" className="close" title="Close" onClick={onClose}>
              ✕
            </button>
          </div>
        </header>
        <div className="content">{children}</div>
        <footer>
          {footerExtra}
          {cancelLabel !== null && (
            <button type="button" className="cancel" onClick={onClose}>
              {cancelLabel}
            </button>
          )}
          {!hideSave && onSave && (
            <button type="button" className="save" disabled={saving || saveDisabled} onClick={handleSave}>
              {saveLabel}
            </button>
          )}
        </footer>
      </div>
    </div>
  );
}
