import { showError, showSuccess } from "../lib/toast";

// Material "content_copy" icon.
function CopyIcon() {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" width="1em" height="1em" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
      <path d="M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z" />
    </svg>
  );
}

export function CopyButton({ value, label = "Copy to clipboard" }: { value: string; label?: string }) {
  async function copy() {
    try {
      await navigator.clipboard.writeText(value);
      showSuccess("Copied to clipboard");
    } catch {
      showError("Unable to copy to clipboard");
    }
  }
  return (
    <button
      type="button"
      onClick={copy}
      title={label}
      aria-label={label}
      style={{ background: "none", border: "none", padding: "0 4px", cursor: "pointer", color: "inherit", verticalAlign: "-0.15em", lineHeight: 1 }}
    >
      <CopyIcon />
    </button>
  );
}
