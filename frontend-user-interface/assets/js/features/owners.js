/**
 * Owners feature.
 *
 * Public surface:
 *   - initOwners(ctx)   Wire the tab once. ctx = { getApi }.
 *   - loadOwners()      Fetch + render the owners list (called from setActiveTab).
 *
 * Side effect: the owners list also populates the `<select id="store-owner">`
 * used by the (legacy) stores tab — kept for compatibility with existing markup.
 */

import { $, escapeHtml, wireOnce } from '../lib/dom.js';
import { openModal } from '../lib/modal.js';
import { resolveMediaAccessKey } from '../lib/media-resolver.js';
import { showError, showWarning } from '../lib/toast.js';
import { clearOwnersMap } from '../lib/owners-map.js';

let ctx = null;
const api = () => ctx.getApi();

export function initOwners(context) {
  ctx = context;
  wireOnce(document.getElementById('refresh-owners'), 'click', loadOwners);
  wireOnce(document.getElementById('create-owner'), 'click', () => openOwnerCreateModal());
}

export async function loadOwners() {
  const list = $('#owners-list');
  if (!list) return;
  list.innerHTML = '';
  try {
    const owners = await api().listOwners();
    refreshOwnerSelect(owners);

    const limit = 4;
    let inFlight = 0;
    const pending = [];
    const scheduled = new WeakSet();

    async function resolveAndRenderOwnerThumb(li, owner) {
      inFlight++;
      try {
        if (!owner.originalId) return;
        const key = await resolveMediaAccessKey(api(), owner.originalId);
        if (!key) return;
        const img = new Image();
        img.src = api().mediaMiniatureUrl(key);
        img.alt = `${owner.firstName} ${owner.lastName}`;
        img.loading = 'lazy';
        img.decoding = 'async';
        img.style.width = '100%';
        img.style.height = '100%';
        img.style.objectFit = 'cover';
        img.style.display = 'block';
        const ph = li.querySelector('.owner-thumb');
        if (ph) { ph.innerHTML = ''; ph.style.background = 'transparent'; ph.appendChild(img); }
      } finally {
        inFlight--;
        scheduleOwnerThumb();
      }
    }

    function scheduleOwnerThumb() {
      while (inFlight < limit && pending.length > 0) {
        const item = pending.shift();
        resolveAndRenderOwnerThumb(item.li, item.owner);
      }
    }

    const tabSection = document.getElementById('tab-owners');
    const observer = ('IntersectionObserver' in window)
      ? new IntersectionObserver((entries) => {
          for (const entry of entries) {
            if (entry.isIntersecting) {
              const li = entry.target;
              observer.unobserve(li);
              if (scheduled.has(li)) continue;
              const owner = li.__owner;
              scheduled.add(li);
              if (owner && owner.originalId) { pending.push({ li, owner }); scheduleOwnerThumb(); }
            }
          }
        }, { root: tabSection, rootMargin: '200px 0px', threshold: 0.01 })
      : null;

    for (const o of owners) {
      const li = document.createElement('li');
      li.__owner = o;
      li.dataset.ownerId = o.id || '';
      const birthStr = o.birthDate ? new Date(o.birthDate).toLocaleDateString() : '';
      li.innerHTML = `
        <div style="display: flex; align-items: center; gap: 12px;">
          <div class="owner-thumb list-thumb list-thumb-sm">No image</div>
          <div style="flex: 1;">
            <h4 style="margin: 0 0 4px 0;">${o.firstName} ${o.lastName}</h4>
            <div style="font-size:12px;color:#555">id: ${o.id}${birthStr ? ' • birth: ' + birthStr : ''}</div>
          </div>
          <button class="ev-edit-btn" title="Edit">✎ Edit</button>
        </div>
      `;
      list.appendChild(li);
      const editBtn = li.querySelector('.ev-edit-btn');
      if (editBtn) { editBtn.onclick = (e) => { e.stopPropagation(); openOwnerEditModal(o); }; }

      if (observer && o.originalId) {
        observer.observe(li);
      } else if (o.originalId) {
        pending.push({ li, owner: o }); scheduleOwnerThumb();
      }
    }
  } catch (e) {
    list.innerHTML = '<li>Failed to load owners</li>';
  }
}

function refreshOwnerSelect(owners) {
  const ownerSelect = document.getElementById('store-owner');
  if (ownerSelect) ownerSelect.innerHTML = owners.map((o) => `<option value="${o.id}">${o.firstName} ${o.lastName}</option>`).join('');
}

function refreshOwnerTile(updated) {
  const list = document.getElementById('owners-list');
  if (!list) return;
  const li = list.querySelector(`li[data-owner-id="${updated.id}"]`);
  if (!li) return;
  const birthStr = updated.birthDate ? new Date(updated.birthDate).toLocaleDateString() : '';
  li.innerHTML = `<h4>${updated.firstName} ${updated.lastName}</h4><div style="font-size:12px;color:#555">id: ${updated.id}${birthStr ? ' • birth: ' + birthStr : ''}</div>
    <button class="ev-edit-btn" title="Edit">✎ Edit</button>`;
  const editBtn = li.querySelector('.ev-edit-btn');
  if (editBtn) { editBtn.onclick = (e) => { e.stopPropagation(); openOwnerEditModal(updated); }; }
}

function toDateInput(d) {
  try {
    if (!d) return '';
    const yyyy = d.getFullYear();
    const mm = String(d.getMonth() + 1).padStart(2, '0');
    const dd = String(d.getDate()).padStart(2, '0');
    return `${yyyy}-${mm}-${dd}`;
  } catch { return ''; }
}

function openOwnerEditModal(owner) {
  if (!owner) { showWarning('No owner'); return; }
  const birthVal = owner.birthDate ? new Date(owner.birthDate) : null;
  openModal({
    title: 'Edit owner',
    focusSelector: '#ow-first',
    body: `
      <div class="row">
        <div>
          <label>First name</label>
          <input type="text" id="ow-first" value="${escapeHtml(owner.firstName)}">
          <label class="form-label">Last name</label>
          <input type="text" id="ow-last" value="${escapeHtml(owner.lastName)}">
          <label class="form-label">Birthdate</label>
          <input type="date" id="ow-birth" value="${toDateInput(birthVal)}">
        </div>
      </div>`,
    onSave: async ({ modal }) => {
      const firstName = modal.querySelector('#ow-first').value.trim();
      const lastName = modal.querySelector('#ow-last').value.trim();
      const birth = modal.querySelector('#ow-birth').value;
      const body = { firstName, lastName };
      body.birthDate = birth ? `${birth}T00:00:00Z` : null;
      try {
        await api().updateOwner(owner.id, body);
        const owners = await api().listOwners();
        const updated = owners.find((x) => x.id === owner.id) || { ...owner, firstName, lastName, birthDate: birth || null };
        refreshOwnerTile(updated);
        clearOwnersMap();
        refreshOwnerSelect(owners);
      } catch (e) {
        showError('Failed to update owner');
        return false;
      }
    },
  });
}

function openOwnerCreateModal() {
  openModal({
    title: 'Create owner',
    saveLabel: 'Create',
    focusSelector: '#owc-first',
    body: `
      <div class="row">
        <div>
          <label>First name</label>
          <input type="text" id="owc-first" value="">
          <label class="form-label">Last name</label>
          <input type="text" id="owc-last" value="">
          <label class="form-label">Birthdate</label>
          <input type="date" id="owc-birth" value="">
        </div>
      </div>`,
    onSave: async ({ modal }) => {
      const firstName = modal.querySelector('#owc-first').value.trim();
      const lastName = modal.querySelector('#owc-last').value.trim();
      const birth = modal.querySelector('#owc-birth').value;
      if (!firstName || !lastName) { showWarning('First name and Last name are required'); return false; }
      const body = { firstName, lastName };
      body.birthDate = birth ? `${birth}T00:00:00Z` : null;
      try {
        await api().createOwner(body);
        await loadOwners();
        clearOwnersMap();
        try {
          const owners = await api().listOwners();
          refreshOwnerSelect(owners);
        } catch {}
      } catch (e) {
        showError('Failed to create owner');
        return false;
      }
    },
  });
}
