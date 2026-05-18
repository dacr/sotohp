/**
 * Events feature.
 *
 * Public surface:
 *   - initEvents(ctx)            Wire the tab once. ctx = { getApi, setActiveTab, goToMosaicAtTimestamp }.
 *   - ensureEventsLoaded()       Load the events list if not already loaded.
 *   - goToEventsById(eventId)    Switch to the Events tab and scroll to that event tile.
 *   - openEventEditModal(ev)     Open the event edit dialog (called from media-edit / viewer info panel).
 *
 * `getApi()` is called lazily because `app.js` reassigns the ApiClient singleton during Keycloak init.
 */

import { $, escapeHtml, wireOnce } from '../lib/dom.js';
import { openModal } from '../lib/modal.js';
import { resolveMediaAccessKey } from '../lib/media-resolver.js';
import { showError, showWarning } from '../lib/toast.js';
import { toLocalInputValue, fromLocalInputValue } from '../lib/datetime.js';

let ctx = null;
let eventsLoaded = false;

const api = () => ctx.getApi();

// ---------------------------------------------------------------------------
// Public entry points
// ---------------------------------------------------------------------------

export function initEvents(context) {
  ctx = context;
  wireOnce(document.getElementById('refresh-events'), 'click', () => loadEvents({ force: true }));
  wireOnce(document.getElementById('create-event'), 'click', () => openEventCreateModal());

  // Wire scroll persistence once
  const sec = document.getElementById('tab-events');
  if (sec && !sec.__scrollWired) {
    let timer = null;
    const saveNow = () => {
      try {
        setSavedEventsScrollTop(sec.scrollTop);
        const rect = sec.getBoundingClientRect();
        const x = rect.left + 24; const y = rect.top + rect.height / 2;
        const el = document.elementFromPoint(x, y);
        const li = el && el.closest ? el.closest('#events-list li') : null;
        if (li && li.dataset && li.dataset.eventId) setSavedEventsAnchorId(li.dataset.eventId);
      } catch {}
    };
    const onScroll = () => { if (timer) clearTimeout(timer); timer = setTimeout(saveNow, 120); };
    sec.addEventListener('scroll', onScroll, { passive: true });
    setTimeout(saveNow, 0);
    sec.__scrollWired = true;
  }
  // Make the Events section focusable and keep focus for keyboard scrolling
  if (sec && !sec.__focusWired) {
    try { sec.setAttribute('tabindex', '0'); } catch {}
    try { sec.setAttribute('aria-label', 'Events'); } catch {}
    const focusEvents = () => { try { sec.focus({ preventScroll: true }); } catch { try { sec.focus(); } catch {} } };
    sec.addEventListener('mouseenter', focusEvents, { passive: true });
    sec.addEventListener('mousemove', focusEvents, { passive: true });
    sec.addEventListener('wheel', focusEvents, { passive: true });
    const list = document.getElementById('events-list');
    if (list) {
      list.addEventListener('mouseenter', focusEvents, { passive: true });
      list.addEventListener('mousemove', focusEvents, { passive: true });
      list.addEventListener('wheel', focusEvents, { passive: true });
    }
    sec.__focusWired = true;
  }
}

export function ensureEventsLoaded() {
  try { if (!eventsLoaded) loadEvents({ force: false }); } catch {}
}

export async function goToEventsById(eventId) {
  try {
    ctx.setActiveTab('events');
    let tries = 0;
    let li = null;
    while (tries < 120) { // up to ~6s
      li = document.querySelector(`#events-list li[data-event-id="${eventId}"]`);
      if (li) break;
      await new Promise((r) => setTimeout(r, 50));
      tries++;
    }
    if (!li) {
      try { await loadEvents({ force: false }); } catch {}
      li = document.querySelector(`#events-list li[data-event-id="${eventId}"]`);
    }
    if (li) {
      try { li.scrollIntoView({ behavior: 'smooth', block: 'center' }); } catch { li.scrollIntoView(true); }
      li.classList.add('highlight');
      setTimeout(() => { try { li.classList.remove('highlight'); } catch {} }, 1500);
    }
  } catch {}
}

// ---------------------------------------------------------------------------
// Internal: scroll persistence
// ---------------------------------------------------------------------------

function getSavedEventsScrollTop() { try { return parseInt(localStorage.getItem('events.scrollTop') || '0', 10) || 0; } catch { return 0; } }
function setSavedEventsScrollTop(v) { try { localStorage.setItem('events.scrollTop', String(Math.max(0, v | 0))); } catch {} }
function getSavedEventsAnchorId() { try { return localStorage.getItem('events.anchorId'); } catch { return null; } }
function setSavedEventsAnchorId(id) { try { if (id) localStorage.setItem('events.anchorId', id); } catch {} }

function restoreEventsScrollState() {
  try {
    const sec = document.getElementById('tab-events');
    if (!sec) return;
    const anchor = getSavedEventsAnchorId();
    if (anchor) {
      const li = document.querySelector(`#events-list li[data-event-id="${anchor}"]`);
      if (li) { try { li.scrollIntoView({ behavior: 'auto', block: 'center' }); } catch { li.scrollIntoView(true); } return; }
    }
    const st = getSavedEventsScrollTop();
    if (typeof st === 'number' && st > 0) sec.scrollTop = st;
  } catch {}
}

// ---------------------------------------------------------------------------
// Internal: list rendering
// ---------------------------------------------------------------------------

async function loadEvents(options = {}) {
  const { force = false } = options;
  const list = $('#events-list');
  if (!force && eventsLoaded && list && list.children && list.children.length > 0) {
    try { restoreEventsScrollState(); } catch {}
    return;
  }
  list.innerHTML = '';
  try {
    const events = await api().listEvents();
    events.sort((a, b) => {
      const ta = a.timestamp ? Date.parse(a.timestamp) : 0;
      const tb = b.timestamp ? Date.parse(b.timestamp) : 0;
      return tb - ta;
    });

    // Lazy load state/images per tile when needed
    const limit = 4;
    let inFlight = 0;
    const pending = [];
    const scheduled = new WeakSet();

    async function resolveAndRender(li, ev) {
      inFlight++;
      try {
        if (!ev.originalId) return;
        const key = await resolveMediaAccessKey(api(), ev.originalId);
        if (!key) return;
        const img = new Image();
        img.src = api().mediaNormalizedUrl(key);
        img.alt = ev.name || '';
        img.loading = 'lazy';
        img.decoding = 'async';
        img.style.width = '100%';
        img.style.height = '100%';
        img.style.objectFit = 'cover';
        img.style.display = 'block';
        const ph = li.querySelector('.ev-thumb');
        if (ph) { ph.innerHTML = ''; ph.style.background = 'transparent'; ph.appendChild(img); }
        li.style.cursor = 'pointer';
        li.onclick = async () => {
          try { await ctx.goToMosaicAtTimestamp(ev.timestamp); } catch {}
        };
      } finally {
        inFlight--;
        schedule();
      }
    }

    function schedule() {
      while (inFlight < limit && pending.length > 0) {
        const item = pending.shift();
        resolveAndRender(item.li, item.ev);
      }
    }

    const tabSection = document.getElementById('tab-events');
    const observer = ('IntersectionObserver' in window)
      ? new IntersectionObserver((entries) => {
          for (const entry of entries) {
            if (entry.isIntersecting) {
              const li = entry.target;
              observer.unobserve(li);
              if (scheduled.has(li)) continue;
              const ev = li.__event;
              scheduled.add(li);
              if (ev && ev.originalId) { pending.push({ li, ev }); schedule(); }
            }
          }
        }, { root: tabSection, rootMargin: '400px 0px', threshold: 0.01 })
      : null;

    for (const ev of events) {
      const li = document.createElement('li');
      li.__event = ev;
      if (ev.id) li.dataset.eventId = ev.id;
      const tsStr = ev.timestamp ? new Date(ev.timestamp).toLocaleString() : '';
      const pinSvgGreen = `<svg xmlns="http://www.w3.org/2000/svg" width="1em" height="1em" viewBox="0 0 24 24" fill="#10b981" style="vertical-align:-0.15em;margin-right:6px"><path d="M12 2c-3.314 0-6 2.686-6 6 0 5 6 12 6 12s6-7 6-12c0-3.314-2.686-6-6-6zm0 10a4 4 0 110-8 4 4 0 010 8z"/></svg>`;
      const tsWithPin = (ev.location ? pinSvgGreen + ' ' : '') + tsStr;
      li.innerHTML = `
        <div class="ev-thumb list-thumb">No preview</div>
        <h4 style="margin:0 0 4px 0;">${ev.name || '(no name)'}</h4>
        <div class="ev-ts" style="font-size:12px;color:#555">${tsWithPin}</div>
        <button class="ev-edit-btn" title="Edit">✎ Edit</button>
      `;
      list.appendChild(li);
      const editBtn = li.querySelector('.ev-edit-btn');
      if (editBtn) { editBtn.onclick = (e) => { e.stopPropagation(); openEventEditModal(ev); }; }

      if (ev.originalId) {
        li.style.cursor = 'pointer';
        li.onclick = async () => {
          try { await ctx.goToMosaicAtTimestamp(ev.timestamp); } catch {}
        };
      }

      if (observer && ev.originalId) {
        observer.observe(li);
      } else if (ev.originalId) {
        pending.push({ li, ev }); schedule();
      }
    }
    eventsLoaded = true;
    setTimeout(() => { try { restoreEventsScrollState(); } catch {} }, 0);
  } catch (e) {
    list.innerHTML = '<li>Failed to load events</li>';
  }
}

function removeEventTile(eventId) {
  try {
    const list = document.getElementById('events-list');
    if (!list) return;
    const li = list.querySelector(`li[data-event-id="${eventId}"]`);
    if (li) li.remove();
  } catch {}
}

function refreshEventTile(ev) {
  try {
    const list = document.getElementById('events-list');
    if (!list) return;
    const li = list.querySelector(`li[data-event-id="${ev.id}"]`);
    if (!li) return;
    li.__event = ev;
    const titleEl = li.querySelector('h4');
    if (titleEl) titleEl.textContent = ev.name || '(no name)';
    const tsEl = li.querySelector('.ev-ts');
    if (tsEl) {
      const tsStr = ev.timestamp ? new Date(ev.timestamp).toLocaleString() : '';
      const pinSvgGreen = `<svg xmlns="http://www.w3.org/2000/svg" width="1em" height="1em" viewBox="0 0 24 24" fill="#10b981" style="vertical-align:-0.15em;margin-right:6px"><path d="M12 2c-3.314 0-6 2.686-6 6 0 5 6 12 6 12s6-7 6-12c0-3.314-2.686-6-6-6zm0 10a4 4 0 110-8 4 4 0 010 8z"/></svg>`;
      const tsWithPin = (ev.location ? pinSvgGreen + ' ' : '') + tsStr;
      tsEl.innerHTML = tsWithPin;
    }
    const editBtn = li.querySelector('.ev-edit-btn');
    if (editBtn) {
      editBtn.onclick = (e) => { e.stopPropagation(); openEventEditModal(ev); };
    }
  } catch {}
}

// ---------------------------------------------------------------------------
// Internal: modals
// ---------------------------------------------------------------------------

function openEventCreateModal() {
  let keywords = [];
  const handle = openModal({
    title: 'Create event',
    saveLabel: 'Create',
    focusSelector: '#evc-name',
    body: `
      <div class="row">
        <div>
          <label>Name</label>
          <input type="text" id="evc-name" value="" required>
          <label class="form-label">Description</label>
          <input type="text" id="evc-desc" value="">
          <label class="form-label">Keywords</label>
          <div class="chips" id="evc-chips"></div>
        </div>
      </div>`,
    onSave: async ({ modal }) => {
      const name = modal.querySelector('#evc-name').value.trim();
      const description = modal.querySelector('#evc-desc').value.trim();
      if (!name) { showWarning('Event name is required'); return false; }
      const body = { name };
      if (description) body.description = description;
      if (keywords.length > 0) body.keywords = keywords;
      try {
        await api().createEvent(body);
        await loadEvents({ force: true });
      } catch (e) {
        showError('Failed to create event');
        return false;
      }
    },
  });
  if (!handle) return;
  const { modal } = handle;
  wireKeywordsChips(modal.querySelector('#evc-chips'), keywords, (next) => { keywords = next; });
}

export function openEventEditModal(ev) {
  if (document.querySelector('.modal-overlay')) { return; }
  const overlay = document.createElement('div');
  overlay.className = 'modal-overlay';
  const nameVal = ev.name || '';
  const descVal = ev.description || '';
  const tsVal = toLocalInputValue(ev.timestamp);
  const pubVal = ev.publishedOn || '';
  overlay.innerHTML = `
    <div class="modal">
      <header>
        <div>Edit event</div>
        <button class="close" title="Close" style="background:none;border:none;font-size:18px;cursor:pointer">✕</button>
      </header>
      <div class="content">
        <div class="row">
          <div>
            <label>Name</label>
            <input type="text" id="ev-name" value="${escapeHtml(nameVal)}">
            <label class="form-label">Description</label>
            <textarea id="ev-desc">${escapeHtml(descVal)}</textarea>
            <label class="form-label">Timestamp</label>
            <input type="datetime-local" id="ev-ts" value="${tsVal}">
            <label class="form-label">Published On (URL)</label>
            <input type="url" id="ev-published" placeholder="https://example.com/album" value="${escapeHtml(pubVal)}">
            <label class="form-label">Keywords</label>
            <div class="chips" id="ev-chips"></div>
          </div>
          <div>
            <label>Location</label>
            <div style="margin:4px 0 6px 0; display:flex; gap:6px; flex-wrap:wrap; align-items:center">
              <button id="ev-remember-loc" type="button" title="Remember current selected location for later reuse" class="btn btn-soft btn-sm">Remember selection</button>
              <button id="ev-use-last-loc" type="button" title="Use last selected location" class="btn btn-soft btn-sm">Use last selection</button>
              <button id="ev-reset-loc" type="button" title="Remove event location" class="btn btn-danger-soft btn-sm">Reset location</button>
            </div>
            <div id="event-edit-map"></div>
          </div>
        </div>
      </div>
      <footer>
        <button type="button" class="btn btn-danger-soft" id="ev-delete-btn" title="Delete event">🗑 Delete</button>
        <span style="flex:1"></span>
        <button type="button" class="cancel">Cancel</button>
        <button type="button" class="save">Save</button>
      </footer>
    </div>`;
  document.body.appendChild(overlay);

  const modalEl = overlay.querySelector('.modal');
  if (modalEl) {
    modalEl.setAttribute('role', 'dialog');
    modalEl.setAttribute('aria-modal', 'true');
    modalEl.setAttribute('tabindex', '-1');
  }
  setTimeout(() => {
    const first = overlay.querySelector('#ev-name');
    if (first && typeof first.focus === 'function') first.focus();
    else if (modalEl && typeof modalEl.focus === 'function') modalEl.focus();
  }, 0);

  function close() { overlay.remove(); }
  overlay.querySelector('button.close')?.addEventListener('click', close);
  overlay.querySelector('button.cancel')?.addEventListener('click', (e) => { e.preventDefault(); e.stopPropagation(); close(); });
  overlay.addEventListener('click', (e) => { if (e.target === overlay) close(); });

  overlay.querySelector('#ev-delete-btn')?.addEventListener('click', async (e) => {
    e.preventDefault();
    e.stopPropagation();
    const label = ev.name ? `"${ev.name}"` : 'this event';
    if (!confirm(`Delete event ${label}? This cannot be undone.`)) return;
    try {
      await api().deleteEvent(ev.id);
      close();
      removeEventTile(ev.id);
    } catch (err) {
      const msg = err?.response?.data?.message || err?.message || 'Failed to delete event';
      showError(msg);
    }
  });

  // Keywords chips
  const chipsEl = overlay.querySelector('#ev-chips');
  let keywords = Array.isArray(ev.keywords) ? [...ev.keywords] : [];
  wireKeywordsChips(chipsEl, keywords, (next) => { keywords = next; });

  // Map setup
  let currentLoc = ev.location ? { ...ev.location } : null;
  let clearedLoc = false;
  const evUseLastBtn = overlay.querySelector('#ev-use-last-loc');
  const evRememberBtn = overlay.querySelector('#ev-remember-loc');
  function evReadLastLoc() { try { const j = localStorage.getItem('ui.lastLocation'); if (!j) return null; const o = JSON.parse(j); if (typeof o?.latitude === 'number' && typeof o?.longitude === 'number') return o; } catch {} return null; }
  function evSaveLastLoc(loc) { try { if (loc && typeof loc.latitude === 'number' && typeof loc.longitude === 'number') localStorage.setItem('ui.lastLocation', JSON.stringify({ latitude: loc.latitude, longitude: loc.longitude, altitude: loc.altitude })); } catch {} }
  function evUpdateUseLastBtn() { const has = !!evReadLastLoc(); if (evUseLastBtn) { evUseLastBtn.disabled = !has; evUseLastBtn.style.opacity = has ? '1' : '0.5'; evUseLastBtn.style.cursor = has ? 'pointer' : 'not-allowed'; } }
  function evUpdateRememberBtn() { const has = !!(currentLoc && typeof currentLoc.latitude === 'number' && typeof currentLoc.longitude === 'number'); if (evRememberBtn) { evRememberBtn.disabled = !has; evRememberBtn.style.opacity = has ? '1' : '0.5'; evRememberBtn.style.cursor = has ? 'pointer' : 'not-allowed'; } }
  setTimeout(() => {
    try {
      const mapDiv = overlay.querySelector('#event-edit-map');
      if (mapDiv) { try { mapDiv.setAttribute('tabindex', '-1'); } catch {} }
      const m = L.map(mapDiv, { keyboard: false }).setView(currentLoc ? [currentLoc.latitude, currentLoc.longitude] : [20, 0], currentLoc ? 13 : 2);
      L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', { attribution: '&copy; OpenStreetMap' }).addTo(m);
      const resetBtn = overlay.querySelector('#ev-reset-loc');
      function enableResetBtn(enable) {
        if (!resetBtn) return;
        resetBtn.disabled = !enable;
        resetBtn.style.opacity = enable ? '1' : '0.5';
        resetBtn.style.cursor = enable ? 'pointer' : 'not-allowed';
      }
      let marker = null;
      function setMarker(latlng) {
        if (!marker) {
          marker = L.marker(latlng, { draggable: true }).addTo(m);
          marker.on('dragend', () => {
            const ll = marker.getLatLng();
            currentLoc = { latitude: ll.lat, longitude: ll.lng, altitude: currentLoc?.altitude };
            clearedLoc = false; enableResetBtn(true);
            evSaveLastLoc(currentLoc);
            evUpdateUseLastBtn();
            evUpdateRememberBtn();
          });
        } else { marker.setLatLng(latlng); }
        currentLoc = { latitude: latlng.lat, longitude: latlng.lng, altitude: currentLoc?.altitude };
        clearedLoc = false; enableResetBtn(true);
        evSaveLastLoc(currentLoc);
        evUpdateUseLastBtn();
        evUpdateRememberBtn();
      }
      if (currentLoc) setMarker({ lat: currentLoc.latitude, lng: currentLoc.longitude });
      m.on('click', (e) => setMarker(e.latlng));
      if (resetBtn) {
        enableResetBtn(!!currentLoc);
        resetBtn.addEventListener('click', () => {
          try { if (marker) m.removeLayer(marker); } catch {}
          marker = null; currentLoc = null; clearedLoc = true; enableResetBtn(false);
          evUpdateRememberBtn();
        });
      }
      evUpdateUseLastBtn();
      evUpdateRememberBtn();
      if (evUseLastBtn) {
        evUseLastBtn.addEventListener('click', () => {
          const last = evReadLastLoc();
          if (!last) return;
          const latlng = { lat: last.latitude, lng: last.longitude };
          setMarker(latlng);
          try { m.setView([last.latitude, last.longitude], Math.max(m.getZoom() || 2, 13)); m.getContainer()?.blur?.(); } catch {}
          try { overlay.querySelector('button.cancel')?.focus(); } catch {}
        });
      }
      if (evRememberBtn) {
        evRememberBtn.addEventListener('click', () => {
          if (!currentLoc) return;
          evSaveLastLoc(currentLoc);
          evUpdateUseLastBtn();
        });
      }
      setTimeout(() => { try { m.invalidateSize(true); } catch {} }, 0);
    } catch {}
  }, 0);

  // Save handler
  overlay.querySelector('button.save')?.addEventListener('click', async () => {
    const name = overlay.querySelector('#ev-name').value.trim();
    if (!name) { showWarning('Name is required'); return; }
    const description = overlay.querySelector('#ev-desc').value.trim();
    const tsLocal = overlay.querySelector('#ev-ts').value;
    const timestamp = fromLocalInputValue(tsLocal);
    const published = (overlay.querySelector('#ev-published')?.value || '').trim();

    const body = { name };
    if (description) body.description = description;
    if (timestamp) body.timestamp = timestamp;
    if (published === '') body.publishedOn = null; else if (published) {
      try { new URL(published); body.publishedOn = published; } catch { showWarning('Invalid Published On URL'); return; }
    }
    if (clearedLoc) body.location = null; else if (currentLoc && typeof currentLoc.latitude === 'number' && typeof currentLoc.longitude === 'number') body.location = currentLoc;
    if (keywords && keywords.length > 0) body.keywords = keywords;
    try {
      await api().updateEvent(ev.id, body);
      const updatedEv = { ...ev };
      updatedEv.name = name || ev.name;
      updatedEv.description = description || undefined;
      updatedEv.timestamp = timestamp || undefined;
      if (published === '') updatedEv.publishedOn = undefined; else if (body.publishedOn) updatedEv.publishedOn = body.publishedOn;
      if (clearedLoc) updatedEv.location = null; else if (body.location) updatedEv.location = body.location;
      if (keywords && keywords.length >= 0) updatedEv.keywords = keywords;
      close();
      refreshEventTile(updatedEv);
    } catch {
      showError('Failed to save event');
    }
  });
}

// ---------------------------------------------------------------------------
// Internal: keyword chips UI
// ---------------------------------------------------------------------------

function wireKeywordsChips(chipsEl, initial, onChange) {
  let keywords = Array.isArray(initial) ? [...initial] : [];
  const input = document.createElement('input');
  input.type = 'text';
  input.placeholder = 'Add keyword and press Enter';
  function commit() { onChange(keywords); }
  function renderChips() {
    chipsEl.innerHTML = '';
    for (const kw of keywords) {
      const chip = document.createElement('span');
      chip.className = 'chip';
      chip.textContent = kw;
      const rm = document.createElement('button');
      rm.className = 'remove'; rm.type = 'button'; rm.textContent = '×';
      rm.addEventListener('click', () => { keywords = keywords.filter((k) => k !== kw); commit(); renderChips(); });
      chip.appendChild(rm);
      chipsEl.appendChild(chip);
    }
    chipsEl.appendChild(input);
  }
  function addKeywordFromInput() {
    const val = input.value.trim();
    if (!val) return;
    if (!keywords.includes(val)) keywords.push(val);
    input.value = '';
    commit();
    renderChips();
    input.focus();
  }
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' || e.key === ',') { e.preventDefault(); addKeywordFromInput(); }
    else if (e.key === 'Backspace' && !input.value && keywords.length > 0) { keywords.pop(); commit(); renderChips(); }
  });
  input.addEventListener('blur', () => { addKeywordFromInput(); });
  renderChips();
}
