/**
 * Tiny DOM helpers shared across feature modules.
 * Pure: no app state, no globals beyond `document`.
 */

export function $(sel, root = document) {
  return root.querySelector(sel);
}

export function $$(sel, root = document) {
  return Array.from(root.querySelectorAll(sel));
}

/** HTML-escape a value for safe interpolation into innerHTML templates. */
export function escapeHtml(value) {
  if (value == null) return '';
  return String(value).replace(/[&<>"']/g, (c) => ({
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;',
  }[c]));
}

/**
 * Attach an event listener to `el` only once across the page lifecycle.
 * Replaces the recurring `if (!el.__wired) { el.addEventListener(...); el.__wired = true; }` idiom.
 *
 * Marker symbols are namespaced per (event, listener identity isn't checked) so
 * calling `wireOnce(btn, 'click', fnA)` and later `wireOnce(btn, 'click', fnB)`
 * still only wires once — same semantics as the original `__wired` flag.
 */
export function wireOnce(el, event, handler) {
  if (!el) return;
  const key = `__wired_${event}`;
  if (el[key]) return;
  el.addEventListener(event, handler);
  el[key] = true;
}

/**
 * Build a DOM element with properties and optional children.
 * Properties prefixed with `_` set attributes directly (for things like `aria-label`).
 *
 *   createEl('button', { className: 'btn', textContent: 'OK' });
 *   createEl('div', { className: 'row' }, [iconEl, labelEl]);
 */
export function createEl(tag, props = {}, children = []) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(props)) {
    if (v == null) continue;
    if (k === 'style' && typeof v === 'object') Object.assign(el.style, v);
    else if (k.startsWith('_')) el.setAttribute(k.slice(1), v);
    else el[k] = v;
  }
  for (const child of [].concat(children)) {
    if (child == null) continue;
    el.appendChild(typeof child === 'string' ? document.createTextNode(child) : child);
  }
  return el;
}
