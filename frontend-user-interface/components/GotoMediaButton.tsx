import { useState, type FormEvent } from "react";

// Material "arrow_forward" icon.
function GotoIcon() {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" width="1em" height="1em" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
      <path d="M12 4l-1.41 1.41L16.17 11H4v2h12.17l-5.58 5.59L12 20l8-8z" />
    </svg>
  );
}

const iconButtonStyle = { background: "none", border: "none", padding: "0 4px", cursor: "pointer", color: "inherit", verticalAlign: "-0.15em", lineHeight: 1 };

/** Icon that unfolds into a small input: type (or paste) another photo's id and press Enter to jump to it. */
export function GotoMediaButton({ onGoto, label = "Go to another photo by id" }: { onGoto: (id: string) => Promise<void>; label?: string }) {
  const [open, setOpen] = useState(false);
  const [value, setValue] = useState("");
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    const id = value.trim();
    if (!id || busy) return;
    setBusy(true);
    try {
      await onGoto(id);
      setOpen(false);
      setValue("");
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <button type="button" onClick={() => setOpen((o) => !o)} title={label} aria-label={label} aria-expanded={open} style={iconButtonStyle}>
        <GotoIcon />
      </button>
      {open && (
        <form onSubmit={submit} style={{ display: "inline" }}>
          <input
            autoFocus
            value={value}
            onChange={(e) => setValue(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Escape") setOpen(false);
            }}
            placeholder="photo id"
            aria-label="Photo id"
            disabled={busy}
            style={{ fontFamily: "monospace", width: "22em", maxWidth: "100%" }}
          />
        </form>
      )}
    </>
  );
}
