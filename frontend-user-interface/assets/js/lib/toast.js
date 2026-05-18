/**
 * Top-of-screen toast notifications.
 * Markup created on demand under `.toast-container`; styled in styles.css.
 *
 *   import { showSuccess, showError } from './lib/toast.js';
 *   showSuccess('Saved');
 *   showError('Failed to load events');
 */

function ensureToastContainer() {
  let container = document.querySelector('.toast-container');
  if (!container) {
    container = document.createElement('div');
    container.className = 'toast-container';
    document.body.appendChild(container);
  }
  return container;
}

export function showToast(message, type = 'info', duration = 4000) {
  const container = ensureToastContainer();
  const toast = document.createElement('div');
  toast.className = `toast ${type}`;
  toast.textContent = message;
  container.appendChild(toast);

  // Trigger show animation
  requestAnimationFrame(() => { toast.classList.add('show'); });

  // Auto-dismiss
  setTimeout(() => {
    toast.classList.remove('show');
    toast.classList.add('hide');
    setTimeout(() => {
      if (toast.parentNode) toast.parentNode.removeChild(toast);
      if (container.children.length === 0) container.remove();
    }, 200);
  }, duration);
}

export function showSuccess(message, duration = 4000) { showToast(message, 'success', duration); }
export function showError(message, duration = 5000)   { showToast(message, 'error',   duration); }
export function showWarning(message, duration = 4500) { showToast(message, 'warning', duration); }
export function showInfo(message, duration = 4000)    { showToast(message, 'info',    duration); }
