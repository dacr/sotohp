/**
 * Stores feature.
 *
 * Public surface:
 *   - initStores(ctx)   Wire the tab once. ctx = { getApi }.
 *   - loadStores()      Fetch + render the stores list (called from setActiveTab).
 */

import { $, escapeHtml, wireOnce } from '../lib/dom.js';
import { openModal } from '../lib/modal.js';
import { resolveMediaAccessKey } from '../lib/media-resolver.js';
import { showError, showWarning } from '../lib/toast.js';
import { getOwnersMap } from '../lib/owners-map.js';

let ctx = null;
const api = () => ctx.getApi();

export function initStores(context) {
  ctx = context;
  wireOnce(document.getElementById('refresh-stores'), 'click', loadStores);
  wireOnce(document.getElementById('create-store'), 'click', () => openStoreCreateModal());
}

export async function loadStores() {
  const list = $('#stores-list');
  if (!list) return;
  list.innerHTML = '';
  try {
    const [stores, ownersMap] = await Promise.all([api().listStores(), getOwnersMap(api())]);

    // Lazy load thumbnails per store using owner originalId when needed
    const ownerCache = new Map(); // ownerId -> Promise<Owner>
    const limit = 4;
    let inFlight = 0;
    const pending = [];
    const scheduled = new WeakSet();

    async function resolveAndRenderStoreThumb(li, store) {
      inFlight++;
      try {
        if (!store.ownerId) return;
        let ownerPromise = ownerCache.get(store.ownerId);
        if (!ownerPromise) {
          ownerPromise = api().listOwners().then((owners) => owners.find((o) => o.id === store.ownerId)).catch((err) => { ownerCache.delete(store.ownerId); throw err; });
          ownerCache.set(store.ownerId, ownerPromise);
        }
        const owner = await ownerPromise;
        if (!owner || !owner.originalId) return;

        const key = await resolveMediaAccessKey(api(), owner.originalId);
        if (!key) return;

        const img = new Image();
        img.src = api().mediaMiniatureUrl(key);
        img.alt = `${store.name || store.baseDirectory}`;
        img.loading = 'lazy';
        img.decoding = 'async';
        img.style.width = '100%';
        img.style.height = '100%';
        img.style.objectFit = 'cover';
        img.style.display = 'block';
        const ph = li.querySelector('.store-thumb');
        if (ph) { ph.innerHTML = ''; ph.style.background = 'transparent'; ph.appendChild(img); }
      } finally {
        inFlight--;
        scheduleStoreThumb();
      }
    }

    function scheduleStoreThumb() {
      while (inFlight < limit && pending.length > 0) {
        const item = pending.shift();
        resolveAndRenderStoreThumb(item.li, item.store);
      }
    }

    const tabSection = document.getElementById('tab-stores');
    const observer = ('IntersectionObserver' in window)
      ? new IntersectionObserver((entries) => {
          for (const entry of entries) {
            if (entry.isIntersecting) {
              const li = entry.target;
              observer.unobserve(li);
              if (scheduled.has(li)) continue;
              const store = li.__store;
              scheduled.add(li);
              if (store && store.ownerId) { pending.push({ li, store }); scheduleStoreThumb(); }
            }
          }
        }, { root: tabSection, rootMargin: '200px 0px', threshold: 0.01 })
      : null;

    for (const s of stores) {
      const li = document.createElement('li');
      li.__store = s;
      li.dataset.storeId = s.id || '';
      const title = `${s.name ? (s.name + ': ') : ''}${s.baseDirectory || ''}`;
      const ownerName = ownersMap.get(s.ownerId) || s.ownerName || s.ownerId || '';
      li.innerHTML = `
        <div style="display: flex; align-items: center; gap: 12px;">
          <div class="store-thumb list-thumb list-thumb-sm">No image</div>
          <div style="flex: 1;">
            <h4 style="margin: 0 0 4px 0;">${title}</h4>
            <div style="font-size:12px;color:#555">id: ${s.id} • owner: ${ownerName}</div>
          </div>
          <button class="ev-edit-btn" title="Edit">✎ Edit</button>
        </div>
      `;
      list.appendChild(li);
      const editBtn = li.querySelector('.ev-edit-btn');
      if (editBtn) {
        editBtn.onclick = (e) => { e.stopPropagation(); openStoreEditModal(s); };
      }

      if (observer && s.ownerId) {
        observer.observe(li);
      } else if (s.ownerId) {
        pending.push({ li, store: s }); scheduleStoreThumb();
      }
    }
  } catch (e) {
    list.innerHTML = '<li>Failed to load stores</li>';
  }
}

function refreshStoreTile(updated) {
  const list = document.getElementById('stores-list');
  if (!list) return;
  const li = list.querySelector(`li[data-store-id="${updated.id}"]`);
  if (!li) return;
  getOwnersMap(api()).then((ownersMap) => {
    const title = `${updated.name ? (updated.name + ': ') : ''}${updated.baseDirectory || ''}`;
    const ownerName = ownersMap.get(updated.ownerId) || updated.ownerName || updated.ownerId || '';
    li.innerHTML = `
      <h4>${title}</h4>
      <div style="font-size:12px;color:#555">id: ${updated.id} • owner: ${ownerName}</div>
      <button class="ev-edit-btn" title="Edit">✎ Edit</button>
    `;
    const editBtn = li.querySelector('.ev-edit-btn');
    if (editBtn) { editBtn.onclick = (e) => { e.stopPropagation(); openStoreEditModal(updated); }; }
  });
}

function openStoreEditModal(store) {
  if (!store) { showWarning('No store'); return; }
  openModal({
    title: 'Edit store',
    focusSelector: '#st-name',
    body: `
      <div class="row">
        <div>
          <label>Name</label>
          <input type="text" id="st-name" value="${escapeHtml(store.name)}">
          <label class="form-label">Base directory</label>
          <input type="text" id="st-basedir" placeholder="/path/to/base/directory" value="${escapeHtml(store.baseDirectory)}">
          <label class="form-label">Include mask</label>
          <input type="text" id="st-include" value="${escapeHtml(store.includeMask)}">
          <label class="form-label">Ignore mask</label>
          <input type="text" id="st-ignore" value="${escapeHtml(store.ignoreMask)}">
        </div>
      </div>`,
    onSave: async ({ modal }) => {
      const name = modal.querySelector('#st-name').value;
      const baseDirectory = modal.querySelector('#st-basedir').value;
      const includeMask = modal.querySelector('#st-include').value;
      const ignoreMask = modal.querySelector('#st-ignore').value;
      const body = { name, baseDirectory, includeMask, ignoreMask };
      try {
        await api().updateStore(store.id, body);
        const stores = await api().listStores();
        const updated = stores.find((x) => x.id === store.id) || { ...store, name, baseDirectory, includeMask, ignoreMask };
        refreshStoreTile(updated);
      } catch (e) {
        showError('Failed to update store');
        return false;
      }
    },
  });
}

async function openStoreCreateModal() {
  let ownerNames = [];
  const datalistId = 'stc-owner-list';

  function resolveOwnerId(name) {
    const n = (name || '').trim(); if (!n) return null;
    const match = ownerNames.find((o) => o.name.toLowerCase() === n.toLowerCase());
    if (match) return match.id;
    const candidates = ownerNames.filter((o) => o.name.toLowerCase().startsWith(n.toLowerCase()));
    if (candidates.length === 1) return candidates[0].id;
    return null;
  }

  const handle = openModal({
    title: 'Create store',
    saveLabel: 'Create',
    focusSelector: '#stc-name',
    body: `
      <div class="row">
        <div>
          <label>Name</label>
          <input type="text" id="stc-name" value="">
          <label class="form-label">Base directory</label>
          <input type="text" id="stc-basedir" value="" placeholder="/path/to/photos" required>
          <label class="form-label">Owner</label>
          <input type="text" id="stc-owner" list="${datalistId}" placeholder="Loading owners…" autocomplete="off" disabled>
          <datalist id="${datalistId}"></datalist>
          <label class="form-label">Include mask</label>
          <input type="text" id="stc-include" value="">
          <label class="form-label">Ignore mask</label>
          <input type="text" id="stc-ignore" value="">
        </div>
      </div>`,
    onSave: async ({ modal }) => {
      const name = modal.querySelector('#stc-name').value.trim();
      const baseDirectory = modal.querySelector('#stc-basedir').value.trim();
      const ownerName = modal.querySelector('#stc-owner').value.trim();
      const includeMask = modal.querySelector('#stc-include').value.trim();
      const ignoreMask = modal.querySelector('#stc-ignore').value.trim();
      if (!baseDirectory) { showWarning('Base directory is required'); return false; }
      const ownerId = resolveOwnerId(ownerName);
      if (!ownerId) { showWarning('Please select a valid owner by name'); return false; }
      const body = { name: name || null, ownerId, baseDirectory, includeMask: includeMask || null, ignoreMask: ignoreMask || null };
      try {
        await api().createStore(body);
        await loadStores();
      } catch (e) {
        showError('Failed to create store');
        return false;
      }
    },
  });
  if (!handle) return;
  const { modal } = handle;

  try {
    const owners = await api().listOwners();
    ownerNames = owners.map((o) => ({ id: o.id, name: `${o.firstName || ''} ${o.lastName || ''}`.trim() }));
    const optionsHtml = ownerNames.map((o) => `<option value="${escapeHtml(o.name)}"></option>`).join('');
    const dl = modal.querySelector(`#${datalistId}`);
    if (dl) dl.innerHTML = optionsHtml;
    const ownerInput = modal.querySelector('#stc-owner');
    if (ownerInput) {
      ownerInput.disabled = false;
      ownerInput.placeholder = 'Type owner name…';
    }
  } catch (e) {
    const ownerInput = modal.querySelector('#stc-owner');
    if (ownerInput) {
      ownerInput.disabled = true;
      ownerInput.placeholder = 'Failed to load owners';
    }
  }
}
