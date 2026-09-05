/**
 * Top-of-screen toast notifications. Plain DOM, not React state — a toast is fire-and-forget
 * feedback after a mutation, with no state any component needs to read back, so a module-level
 * imperative helper (call `showSuccess('Saved')` from anywhere) is simpler than plumbing a
 * context/provider for it.
 */

export type ToastType = "success" | "error" | "warning" | "info";

function ensureToastContainer(): HTMLDivElement {
  let container = document.querySelector<HTMLDivElement>(".toast-container");
  if (!container) {
    container = document.createElement("div");
    container.className = "toast-container";
    document.body.appendChild(container);
  }
  return container;
}

export function showToast(message: string, type: ToastType = "info", duration = 4000) {
  const container = ensureToastContainer();
  const toast = document.createElement("div");
  toast.className = `toast ${type}`;
  toast.textContent = message;
  container.appendChild(toast);

  requestAnimationFrame(() => toast.classList.add("show"));

  setTimeout(() => {
    toast.classList.remove("show");
    toast.classList.add("hide");
    setTimeout(() => {
      toast.parentNode?.removeChild(toast);
      if (container.children.length === 0) container.remove();
    }, 200);
  }, duration);
}

export const showSuccess = (message: string, duration = 4000) => showToast(message, "success", duration);
export const showError = (message: string, duration = 5000) => showToast(message, "error", duration);
export const showWarning = (message: string, duration = 4500) => showToast(message, "warning", duration);
export const showInfo = (message: string, duration = 4000) => showToast(message, "info", duration);
