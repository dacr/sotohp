/**
 * Modal scaffold helper.
 *
 * Replaces the recurring `open*Modal` boilerplate (overlay + header + content
 * + footer + close handlers + single-instance guard) with one call.
 *
 *   const { modal, close } = openModal({
 *     title: 'Create portfolio',
 *     body: `<label>Name</label><input id="pfc-name" required>`,
 *     onSave: async ({ modal, close }) => {
 *       const name = modal.querySelector('#pfc-name').value.trim();
 *       if (!name) return false; // prevent close
 *       await api.createPortfolio({ name });
 *       // returning undefined/true closes the modal
 *     },
 *     focusSelector: '#pfc-name',
 *   });
 *
 * Options:
 *   - title         : header text.
 *   - body          : string (innerHTML), HTMLElement, or (api) => string|HTMLElement
 *                     `api` is `{ modal, close }` when called.
 *   - onSave        : invoked when the Save button is clicked. Receives `{ modal, close }`.
 *                     Return value: falsy `false`/`Promise<false>` keeps the modal open
 *                     (validation failure). Anything else closes it.
 *   - saveLabel     : default 'Save'.
 *   - cancelLabel   : default 'Cancel' — set to null to hide the Cancel button.
 *   - hideSave      : true to hide the Save button entirely (read-only / picker modals).
 *   - focusSelector : CSS selector for the element to focus after mount.
 *                     Defaults to the first focusable input/textarea/select inside `.content`.
 *   - modalClass    : extra class name(s) appended to `.modal`.
 *   - modalAttrs    : extra inline style string for `.modal` (e.g., 'max-width:760px').
 *   - onClose       : cleanup callback fired when the modal is dismissed.
 *
 * Returned API: `{ overlay, modal, footer, content, close }`.
 */
export function openModal(options) {
  const {
    title,
    body = '',
    onSave,
    saveLabel = 'Save',
    cancelLabel = 'Cancel',
    hideSave = false,
    focusSelector,
    modalClass = '',
    modalAttrs = '',
    onClose,
  } = options || {};

  // Single-instance guard — matches the pre-refactor behavior.
  if (document.querySelector('.modal-overlay')) return null;

  const overlay = document.createElement('div');
  overlay.className = 'modal-overlay';
  const extraClass = modalClass ? ` ${modalClass}` : '';
  const styleAttr = modalAttrs ? ` style="${modalAttrs}"` : '';
  overlay.innerHTML = `
    <div class="modal${extraClass}" role="dialog" aria-modal="true" tabindex="-1"${styleAttr}>
      <header>
        <div>${escapeAttr(title || '')}</div>
        <button type="button" class="close" title="Close">✕</button>
      </header>
      <div class="content"></div>
      <footer></footer>
    </div>`;
  const modal = overlay.querySelector('.modal');
  const content = overlay.querySelector('.content');
  const footer = overlay.querySelector('footer');

  let closed = false;
  function close() {
    if (closed) return;
    closed = true;
    overlay.remove();
    document.removeEventListener('keydown', onKey);
    if (typeof onClose === 'function') {
      try { onClose(); } catch (e) { console.warn('modal onClose threw', e); }
    }
  }
  function onKey(e) { if (e.key === 'Escape') close(); }

  const ctx = { modal, close };

  // Resolve body
  let resolved = body;
  if (typeof resolved === 'function') resolved = resolved(ctx);
  if (resolved instanceof HTMLElement) content.appendChild(resolved);
  else if (typeof resolved === 'string') content.innerHTML = resolved;

  // Footer buttons
  if (cancelLabel) {
    const cancel = document.createElement('button');
    cancel.type = 'button';
    cancel.className = 'cancel';
    cancel.textContent = cancelLabel;
    cancel.addEventListener('click', (e) => { e.preventDefault(); e.stopPropagation(); close(); });
    footer.appendChild(cancel);
  }
  if (!hideSave && typeof onSave === 'function') {
    const save = document.createElement('button');
    save.type = 'button';
    save.className = 'save';
    save.textContent = saveLabel;
    save.addEventListener('click', async () => {
      let result;
      try { result = await onSave(ctx); }
      catch (e) { console.warn('modal onSave threw', e); return; }
      if (result === false) return; // explicit "stay open"
      close();
    });
    footer.appendChild(save);
  }

  // Close wiring
  overlay.querySelector('button.close').addEventListener('click', close);
  overlay.addEventListener('click', (e) => { if (e.target === overlay) close(); });
  document.addEventListener('keydown', onKey);

  document.body.appendChild(overlay);

  // Focus
  setTimeout(() => {
    const target = focusSelector
      ? modal.querySelector(focusSelector)
      : content.querySelector('input, textarea, select');
    (target || modal).focus({ preventScroll: true });
  }, 0);

  return { overlay, modal, content, footer, close };
}

function escapeAttr(v) {
  return String(v).replace(/[&<>"']/g, (c) => ({ '&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;' }[c]));
}
