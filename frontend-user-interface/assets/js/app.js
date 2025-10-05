// Compiled JavaScript (manually authored) corresponding to TypeScript sources.
// Uses axios (via CDN) and Leaflet (via CDN). ES module for clarity.

class ApiClient {
  constructor(baseURL = '') { this.http = axios.create({ baseURL }); }
  async getMedia(select, referenceMediaAccessKey, referenceMediaTimestamp) {
    const params = { select };
    if (referenceMediaAccessKey) params.referenceMediaAccessKey = referenceMediaAccessKey;
    if (referenceMediaTimestamp) params.referenceMediaTimestamp = referenceMediaTimestamp;
    const res = await this.http.get('/api/media', { params });
    return res.data;
  }
  async getMediaByKey(mediaAccessKey) {
    const res = await this.http.get(`/api/media/${encodeURIComponent(mediaAccessKey)}`);
    return res.data;
  }
  mediaNormalizedUrl(mediaAccessKey) { return `/api/media/${encodeURIComponent(mediaAccessKey)}/content/normalized`; }
  mediaMiniatureUrl(mediaAccessKey) { return `/api/media/${encodeURIComponent(mediaAccessKey)}/content/miniature`; }
  async listEvents() { return await this.#fetchNdjson('/api/events'); }
  async getState(originalId) { const res = await this.http.get(`/api/state/${encodeURIComponent(originalId)}`); return res.data; }
  async createEvent(body) { const res = await this.http.post('/api/event', body); return res.data; }
  async updateEvent(eventId, body) { await this.http.put(`/api/event/${encodeURIComponent(eventId)}`, body); }
  async updateMedia(mediaAccessKey, body) { await this.http.put(`/api/media/${encodeURIComponent(mediaAccessKey)}`, body); }
  async updateMediaStarred(mediaAccessKey, state) { await this.http.put(`/api/media/${encodeURIComponent(mediaAccessKey)}/starred`, null, { params: { state } }); }
  async listOwners() { return await this.#fetchNdjson('/api/owners'); }
  async getOwner(ownerId) { const res = await this.http.get(`/api/owner/${encodeURIComponent(ownerId)}`); return res.data; }
  async updateOwner(ownerId, body) { await this.http.put(`/api/owner/${encodeURIComponent(ownerId)}`, body); }
  async createOwner(body) { const res = await this.http.post('/api/owner', body); return res.data; }
  async listStores() { return await this.#fetchNdjson('/api/stores'); }
  async getStore(storeId) { const res = await this.http.get(`/api/store/${encodeURIComponent(storeId)}`); return res.data; }
  async updateStore(storeId, body) { await this.http.put(`/api/store/${encodeURIComponent(storeId)}`, body); }
  async createStore(body) { const res = await this.http.post('/api/store', body); return res.data; }
  async synchronizeStatus() { const res = await this.http.get('/api/admin/synchronize'); return res.data; }
  async synchronizeStart() { await this.http.put('/api/admin/synchronize'); }
  async setEventCover(eventId, mediaAccessKey) { await this.http.put(`/api/event/${encodeURIComponent(eventId)}/cover/${encodeURIComponent(mediaAccessKey)}`); }
  async setOwnerCover(ownerId, mediaAccessKey) { await this.http.put(`/api/owner/${encodeURIComponent(ownerId)}/cover/${encodeURIComponent(mediaAccessKey)}`); }
  async mediasWithLocations(onItem) { await this.#fetchNdjsonStream('/api/medias?filterHasLocation=true', onItem); }
  async #fetchNdjson(url) {
    const res = await this.http.get(url, { responseType: 'text' });
    const items = [];
    const lines = (res.data || '').split(/\r?\n/);
    for (const line of lines) { if (!line.trim()) continue; try { items.push(JSON.parse(line)); } catch {} }
    return items;
  }
  async #fetchNdjsonStream(url, onItem) {
    if (!('fetch' in window)) { (await this.#fetchNdjson(url)).forEach(onItem); return; }
    const res = await fetch(url);
    const reader = res.body?.getReader(); if (!reader) return;
    const decoder = new TextDecoder(); let buffer = '';
    while (true) {
      const { done, value } = await reader.read(); if (done) break;
      buffer += decoder.decode(value, { stream: true });
      let idx; while ((idx = buffer.indexOf('\n')) >= 0) {
        const line = buffer.slice(0, idx); buffer = buffer.slice(idx + 1);
        if (line.trim().length === 0) continue; try { onItem(JSON.parse(line)); } catch {}
      }
    }
    if (buffer.trim().length > 0) { try { onItem(JSON.parse(buffer)); } catch {} }
  }
}

const api = new ApiClient('');
let currentMedia = null;
let slideshowTimer = null;
let slideshowPlaying = false;

// Owner caching to reduce API requests
const ownerCache = new Map(); // ownerId -> owner object
const storeCache = new Map(); // storeId -> store object

// Toast notification system
function ensureToastContainer() {
  let container = document.querySelector('.toast-container');
  if (!container) {
    container = document.createElement('div');
    container.className = 'toast-container';
    document.body.appendChild(container);
  }
  return container;
}

function showToast(message, type = 'info', duration = 4000) {
  const container = ensureToastContainer();
  const toast = document.createElement('div');
  toast.className = `toast ${type}`;
  toast.textContent = message;
  
  container.appendChild(toast);
  
  // Trigger show animation
  requestAnimationFrame(() => {
    toast.classList.add('show');
  });
  
  // Auto-dismiss
  setTimeout(() => {
    toast.classList.remove('show');
    toast.classList.add('hide');
    setTimeout(() => {
      if (toast.parentNode) {
        toast.parentNode.removeChild(toast);
      }
      // Clean up container if empty
      if (container.children.length === 0) {
        container.remove();
      }
    }, 200);
  }, duration);
}

function showSuccess(message, duration = 4000) {
  showToast(message, 'success', duration);
}

function showError(message, duration = 5000) {
  showToast(message, 'error', duration);
}

function showWarning(message, duration = 4500) {
  showToast(message, 'warning', duration);
}

function showInfo(message, duration = 4000) {
  showToast(message, 'info', duration);
}

function $(sel) { return document.querySelector(sel); }
function setActiveTab(name) {
  document.querySelectorAll('nav.tabs button').forEach(b => b.classList.toggle('active', b.dataset.tab === name));
  document.querySelectorAll('main .tab').forEach(s => s.classList.toggle('active', s.id === `tab-${name}`));
  location.hash = `#${name}`;
  if (name === 'world') {
    ensureMap();
    // Force Leaflet to recalculate dimensions when tab becomes visible
    setTimeout(() => { if (map) { map.invalidateSize(true); } }, 0);
    // If for any reason no markers are present and we're not loading, try to (re)load data
    setTimeout(() => {
      try {
        const hasMarkers = !!(cluster && typeof cluster.getLayers === 'function' && cluster.getLayers().length > 0);
        if (!hasMarkers && !mapLoading) loadMapData({ clear: false });
      } catch {}
    }, 50);
  }
  if (name === 'mosaic') loadMosaic();
  if (name === 'events') loadEvents();
  if (name === 'owners') loadOwners();
  if (name === 'stores') loadStores();
  if (name === 'settings') {
    // Start sync polling when Settings tab becomes active
    refreshSyncStatus();
    startSyncPolling();
  } else {
    // Stop sync polling when switching away from Settings tab
    stopSyncPolling();
  }
}

function initTabs() {
  document.querySelectorAll('nav.tabs button').forEach(btn => btn.addEventListener('click', () => setActiveTab(btn.dataset.tab)));
  const initial = location.hash?.slice(1) || 'viewer';
  setActiveTab(initial);
}

async function loadMedia(select, referenceKey) {
  try {
    const media = await api.getMedia(select, referenceKey);
    showMedia(media);
  } catch (e) { console.warn('getMedia failed', e); }
}

function showMedia(media) {
  currentMedia = media;
  try { localStorage.setItem('viewer.lastMediaAccessKey', media.accessKey); } catch {}
  const img = $('#main-image');
  img.src = api.mediaNormalizedUrl(media.accessKey) + `?t=${Date.now()}`; // cache bust
  const date = media.shootDateTime || media.original?.cameraShootDateTime || '-';
  const dateStr = date ? new Date(date).toLocaleString() : '-';
  const eventName = (media.events && media.events.length > 0) ? media.events[0].name : '-';
  $('#info-date').textContent = dateStr;
  $('#info-event').textContent = eventName;
  const starInfoEl = document.getElementById('info-starred'); if (starInfoEl) starInfoEl.textContent = media.starred ? '⭐ Yes' : '☆ No';
  const hasLoc = !!(media.location || media.userDefinedLocation || media.deductedLocation);
  // Show colored location pin like fullscreen overlay
  const pinSvgInfo = (color) => `\
<svg xmlns="http://www.w3.org/2000/svg" width="1em" height="1em" viewBox="0 0 24 24" fill="${color}" style="vertical-align:-0.15em;margin-right:6px"><path d="M12 2c-3.314 0-6 2.686-6 6 0 5 6 12 6 12s6-7 6-12c0-3.314-2.686-6-6-6zm0 10a4 4 0 110-8 4 4 0 010 8z"/></svg>`;
  let pinColor = '#ef4444';
  let locLabel = 'No';
  if (media.location) { pinColor = '#10b981'; locLabel = 'Known'; }
  else if (media.userDefinedLocation || media.deductedLocation) { pinColor = '#f59e0b'; locLabel = 'Estimated'; }
  const hasLocEl = document.getElementById('info-hasloc');
  hasLocEl.innerHTML = `${pinSvgInfo(pinColor)} ${locLabel}`;
  // Make clickable only when true GPS location is known (green)
  hasLocEl.style.cursor = 'default';
  hasLocEl.title = '';
  hasLocEl.onclick = null;
  if (media.location) {
    hasLocEl.style.cursor = 'pointer';
    hasLocEl.title = 'Show on map';
    hasLocEl.onclick = () => goToWorldLocation(media.location, media.accessKey);
  }
  
  // Fetch and display owner information
  const ownerEl = document.getElementById('info-owner');
  if (ownerEl && media.original?.storeId) {
    ownerEl.textContent = 'Loading...';
    (async () => {
      try {
        let store = storeCache.get(media.original.storeId);
        if (!store) {
          store = await api.getStore(media.original.storeId);
          storeCache.set(media.original.storeId, store);
        }
        
        if (store?.ownerId) {
          let owner = ownerCache.get(store.ownerId);
          if (!owner) {
            owner = await api.getOwner(store.ownerId);
            ownerCache.set(store.ownerId, owner);
          }
          
          if (owner?.firstName && owner?.lastName) {
            ownerEl.textContent = `${owner.firstName} ${owner.lastName}`;
          } else {
            ownerEl.textContent = '-';
          }
        } else {
          ownerEl.textContent = '-';
        }
      } catch (e) {
        console.warn('Failed to fetch owner info:', e);
        ownerEl.textContent = '-';
      }
    })();
  } else if (ownerEl) {
    ownerEl.textContent = '-';
  }
  
  {
    const kwEl = document.getElementById('info-keywords');
    const kws = Array.isArray(media.keywords) ? media.keywords : [];
    if (kws.length > 0) {
      const esc = (s) => String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/"/g,'&quot;');
      const chips = kws.map(k => `<span class="chip">${esc(k)}</span>`).join(' ');
      kwEl.innerHTML = `<span class="kw-chips">${chips}</span>`;
    } else {
      kwEl.textContent = '-';
    }
  }
  // Update fullscreen overlay content
  const ov = document.getElementById('fs-overlay');
  if (ov) {
    const star = media.starred ? '⭐ ' : '☆ ';
    // Choose a colored pin depending on location source
    const pinSvg = (color) => `\
<svg xmlns="http://www.w3.org/2000/svg" width="1em" height="1em" viewBox="0 0 24 24" fill="${color}" style="vertical-align:-0.15em;margin-left:6px"><path d="M12 2c-3.314 0-6 2.686-6 6 0 5 6 12 6 12s6-7 6-12c0-3.314-2.686-6-6-6zm0 10a4 4 0 110-8 4 4 0 010 8z"/></svg>`;
    let pin = '';
    if (media.location) {
      pin = pinSvg('#10b981'); // green for known location
    } else if (media.userDefinedLocation || media.deductedLocation) {
      pin = pinSvg('#f59e0b'); // orange for user-defined or deducted
    } else {
      pin = pinSvg('#ef4444'); // red for unknown
    }
    // In fullscreen, only show the event information (no timestamp)
    ov.innerHTML = `<div class="title">${star}${eventName} ${pin}</div>`;
  }
  // Update Star toggle button in controls
  const starBtn = document.getElementById('btn-star');
  if (starBtn) {
    starBtn.textContent = media.starred ? '⭐' : '☆';
    starBtn.title = media.starred ? 'Unstar' : 'Star';
    starBtn.onclick = async () => {
      if (!currentMedia) return;
      const target = !currentMedia.starred;
      const prev = currentMedia.starred;
      starBtn.disabled = true;
      try {
        // Optimistic UI update
        currentMedia.starred = target;
        starBtn.textContent = target ? '⭐' : '☆';
        const starInfoEl2 = document.getElementById('info-starred'); if (starInfoEl2) starInfoEl2.textContent = target ? '⭐ Yes' : '☆ No';
        // Update fullscreen overlay star if present
        const ov2 = document.getElementById('fs-overlay');
        if (ov2) {
          const evName = (currentMedia.events && currentMedia.events.length > 0) ? currentMedia.events[0].name : '-';
          const pinSvg2 = (color) => `\
<svg xmlns="http://www.w3.org/2000/svg" width="1em" height="1em" viewBox="0 0 24 24" fill="${color}" style="vertical-align:-0.15em;margin-left:6px"><path d="M12 2c-3.314 0-6 2.686-6 6 0 5 6 12 6 12s6-7 6-12c0-3.314-2.686-6-6-6zm0 10a4 4 0 110-8 4 4 0 010 8z"/></svg>`;
          let pin2 = '';
          if (currentMedia.location) pin2 = pinSvg2('#10b981');
          else if (currentMedia.userDefinedLocation || currentMedia.deductedLocation) pin2 = pinSvg2('#f59e0b');
          else pin2 = pinSvg2('#ef4444');
          ov2.innerHTML = `<div class="title">${target ? '⭐ ' : '☆ '}${evName} ${pin2}</div>`;
        }
        await api.updateMediaStarred(currentMedia.accessKey, target);
      } catch (e) {
        // Revert on failure
        currentMedia.starred = prev;
        starBtn.textContent = prev ? '⭐' : '☆';
        document.getElementById('info-starred').textContent = prev ? '⭐ Yes' : '☆ No';
        showError('Failed to update starred');
      } finally {
        starBtn.disabled = false;
      }
    };
  }
}

function openMediaEditModal(media) {
  if (!media) { showWarning('No media loaded'); return; }
  // Prevent multiple modals from being opened simultaneously (e.g., due to duplicate handlers)
  if (document.querySelector('.modal-overlay')) { return; }
  const overlay = document.createElement('div');
  overlay.className = 'modal-overlay';
  const descVal = media.description || '';
  const tsVal = toLocalInputValue(media.shootDateTime || media.original?.cameraShootDateTime);
  const kwds = Array.isArray(media.keywords) ? [...media.keywords] : [];
  const overlayHtml = `
    <div class="modal">
      <header>
        <div>Edit media</div>
        <button class="close" title="Close" style="background:none;border:none;font-size:18px;cursor:pointer">✕</button>
      </header>
      <div class="content">
        <div style="margin-bottom: 16px; display: flex; gap: 12px; flex-wrap: wrap;">
          <button type="button" id="md-event-cover-btn" style="background:#2563eb;color:#fff;border:1px solid #2563eb;padding:8px 16px;border-radius:20px;cursor:pointer;font-size:14px">Use for event cover</button>
          <button type="button" id="md-owner-cover-btn" style="background:#2563eb;color:#fff;border:1px solid #2563eb;padding:8px 16px;border-radius:20px;cursor:pointer;font-size:14px">Use for owner cover</button>
        </div>
        <div class="row">
          <div>
            <label>Description</label>
            <input type="text" id="md-desc" value="${(descVal||'').replace(/&/g,'&amp;').replace(/"/g,'&quot;')}">
            <label style="margin-top:8px">Shoot date/time</label>
            <input type="datetime-local" id="md-ts" value="${tsVal||''}">
            <label style="margin-top:8px">Keywords</label>
            <div class="chips" id="md-chips"></div>
          </div>
          <div>
            <label>User-defined location</label>
            <div style="margin:4px 0 6px 0; display:flex; gap:6px; flex-wrap:wrap; align-items:center">
              <button id="md-copy-loc" type="button" title="Copy from media GPS location if available" style="background:#f3f4f6;border:1px solid #e5e7eb;padding:4px 8px;border-radius:6px;cursor:pointer">Copy from media location</button>
              <button id="md-remember-loc" type="button" title="Remember current selected location for later reuse" style="background:#f3f4f6;border:1px solid #e5e7eb;padding:4px 8px;border-radius:6px;cursor:pointer">Remember selection</button>
              <button id="md-use-last-loc" type="button" title="Use last selected location" style="background:#f3f4f6;border:1px solid #e5e7eb;padding:4px 8px;border-radius:6px;cursor:pointer">Use last selection</button>
              <button id="md-reset-loc" type="button" title="Remove user-defined location" style="background:#fee2e2;border:1px solid #fecaca;padding:4px 8px;border-radius:6px;cursor:pointer">Reset location</button>
            </div>
            <div id="media-edit-map"></div>
          </div>
        </div>
      </div>
      <footer>
        <button type="button" class="cancel">Cancel</button>
        <button type="button" class="save" style="background:#2563eb;color:#fff;border:1px solid #2563eb;padding:6px 10px;border-radius:6px;cursor:pointer">Save</button>
      </footer>
    </div>`;
  overlay.innerHTML = overlayHtml;
  document.body.appendChild(overlay);

  // Accessibility and focus management
  const modalEl = overlay.querySelector('.modal');
  if (modalEl) {
    modalEl.setAttribute('role', 'dialog');
    modalEl.setAttribute('aria-modal', 'true');
    modalEl.setAttribute('tabindex', '-1');
  }
  setTimeout(() => {
    const first = overlay.querySelector('#md-desc');
    if (first && typeof first.focus === 'function') first.focus();
    else if (modalEl && typeof modalEl.focus === 'function') modalEl.focus();
  }, 0);

  function close() { overlay.remove(); }
  overlay.querySelector('button.close')?.addEventListener('click', close);
  overlay.querySelector('button.cancel')?.addEventListener('click', (e) => { e.preventDefault(); e.stopPropagation(); close(); });
  overlay.addEventListener('click', (e) => { if (e.target === overlay) close(); });

  // Keywords chips
  const chipsEl = overlay.querySelector('#md-chips');
  let keywords = [...kwds];
  const input = document.createElement('input');
  input.type = 'text';
  input.placeholder = 'Add keyword and press Enter';
  function renderChips() {
    chipsEl.innerHTML = '';
    for (const kw of keywords) {
      const chip = document.createElement('span');
      chip.className = 'chip';
      chip.textContent = kw;
      const rm = document.createElement('button');
      rm.className = 'remove'; rm.type = 'button'; rm.textContent = '×';
      rm.addEventListener('click', () => { keywords = keywords.filter(k => k !== kw); renderChips(); });
      chip.appendChild(rm);
      chipsEl.appendChild(chip);
    }
    chipsEl.appendChild(input);
  }
  function addKeywordFromInput() {
    const val = input.value.trim();
    if (!val) return; if (!keywords.includes(val)) keywords.push(val); input.value=''; renderChips(); input.focus();
  }
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' || e.key === ',') { e.preventDefault(); addKeywordFromInput(); }
    else if (e.key === 'Backspace' && !input.value && keywords.length > 0) { keywords.pop(); renderChips(); }
  });
  input.addEventListener('blur', () => { addKeywordFromInput(); });
  renderChips();

  // Map setup for userDefinedLocation (do NOT auto-copy from media.location)
  let currentLoc = media.userDefinedLocation ? { ...media.userDefinedLocation } : null;
  let m = null; let marker = null; let clearedUserLoc = false;
  // Persisted last-selected location helpers
  const mdUseLastBtn = overlay.querySelector('#md-use-last-loc');
  const mdRememberBtn = overlay.querySelector('#md-remember-loc');
  function mdReadLastLoc() { try { const j = localStorage.getItem('ui.lastLocation'); if (!j) return null; const o = JSON.parse(j); if (typeof o?.latitude === 'number' && typeof o?.longitude === 'number') return o; } catch {} return null; }
  function mdSaveLastLoc(loc) { try { if (loc && typeof loc.latitude === 'number' && typeof loc.longitude === 'number') localStorage.setItem('ui.lastLocation', JSON.stringify({ latitude: loc.latitude, longitude: loc.longitude, altitude: loc.altitude })); } catch {} }
  function mdUpdateUseLastBtn() { const has = !!mdReadLastLoc(); if (mdUseLastBtn) { mdUseLastBtn.disabled = !has; mdUseLastBtn.style.opacity = has ? '1' : '0.5'; mdUseLastBtn.style.cursor = has ? 'pointer' : 'not-allowed'; } }
  function mdUpdateRememberBtn() { const has = !!(currentLoc && typeof currentLoc.latitude === 'number' && typeof currentLoc.longitude === 'number'); if (mdRememberBtn) { mdRememberBtn.disabled = !has; mdRememberBtn.style.opacity = has ? '1' : '0.5'; mdRememberBtn.style.cursor = has ? 'pointer' : 'not-allowed'; } }
  setTimeout(() => {
    try {
      const mapDiv = overlay.querySelector('#media-edit-map');
      if (mapDiv) { try { mapDiv.setAttribute('tabindex', '-1'); } catch {} }
      m = L.map(mapDiv, { keyboard: false }).setView(currentLoc ? [currentLoc.latitude, currentLoc.longitude] : [20, 0], currentLoc ? 13 : 2);
      L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', { attribution: '&copy; OpenStreetMap' }).addTo(m);
      const resetBtn = overlay.querySelector('#md-reset-loc');
      function enableResetBtn(enable) {
        if (!resetBtn) return;
        resetBtn.disabled = !enable;
        resetBtn.style.opacity = enable ? '1' : '0.5';
        resetBtn.style.cursor = enable ? 'pointer' : 'not-allowed';
      }
      function setMarker(latlng) {
        if (!marker) {
          marker = L.marker(latlng, { draggable: true }).addTo(m);
          marker.on('dragend', () => {
            const ll = marker.getLatLng();
            currentLoc = { latitude: ll.lat, longitude: ll.lng, altitude: currentLoc?.altitude };
            clearedUserLoc = false;
            enableResetBtn(true);
            mdSaveLastLoc(currentLoc);
            mdUpdateUseLastBtn();
            mdUpdateRememberBtn();
          });
        } else { marker.setLatLng(latlng); }
        currentLoc = { latitude: latlng.lat, longitude: latlng.lng, altitude: currentLoc?.altitude };
        clearedUserLoc = false; enableResetBtn(true);
        mdSaveLastLoc(currentLoc);
        mdUpdateUseLastBtn();
        mdUpdateRememberBtn();
      }
      if (currentLoc) setMarker({ lat: currentLoc.latitude, lng: currentLoc.longitude });
      m.on('click', (e) => setMarker(e.latlng));
      // Wire reset button
      if (resetBtn) {
        enableResetBtn(!!currentLoc);
        resetBtn.addEventListener('click', () => {
          try {
            if (marker && m) { m.removeLayer(marker); }
          } catch {}
          marker = null; currentLoc = null; clearedUserLoc = true; enableResetBtn(false);
          mdUpdateRememberBtn();
        });
      }
      // Wire buttons: Use last selection & Remember selection
      mdUpdateUseLastBtn();
      mdUpdateRememberBtn();
      if (mdUseLastBtn) {
        mdUseLastBtn.addEventListener('click', () => {
          const last = mdReadLastLoc();
          if (!last) return;
          const latlng = { lat: last.latitude, lng: last.longitude };
          setMarker(latlng);
          try { if (m) { m.setView([last.latitude, last.longitude], Math.max(m.getZoom() || 2, 13)); m.getContainer()?.blur?.(); } } catch {}
          try { overlay.querySelector('button.cancel')?.focus(); } catch {}
        });
      }
      if (mdRememberBtn) {
        mdRememberBtn.addEventListener('click', () => {
          if (!currentLoc) return;
          mdSaveLastLoc(currentLoc);
          mdUpdateUseLastBtn();
          // Optional tiny visual feedback could be added later
        });
      }
      setTimeout(() => { try { m.invalidateSize(true); } catch {} }, 0);
    } catch {}
  }, 0);

  // Copy from media location button
  const copyBtn = overlay.querySelector('#md-copy-loc');
  if (copyBtn) {
    if (media.location) {
      copyBtn.disabled = false;
      copyBtn.style.opacity = '1';
      copyBtn.style.cursor = 'pointer';
      copyBtn.addEventListener('click', () => {
        const loc = media.location;
        if (!loc) return;
        currentLoc = { latitude: loc.latitude, longitude: loc.longitude, altitude: loc.altitude };
        try {
          if (m) {
            const latlng = { lat: currentLoc.latitude, lng: currentLoc.longitude };
            if (!marker) { marker = L.marker(latlng, { draggable: true }).addTo(m); marker.on('dragend', () => { const ll = marker.getLatLng(); currentLoc = { latitude: ll.lat, longitude: ll.lng, altitude: currentLoc?.altitude }; mdSaveLastLoc(currentLoc); mdUpdateUseLastBtn(); }); }
            else { marker.setLatLng(latlng); }
            const resetBtn = overlay.querySelector('#md-reset-loc'); if (resetBtn) { resetBtn.disabled = false; resetBtn.style.opacity = '1'; resetBtn.style.cursor = 'pointer'; }
            clearedUserLoc = false; mdSaveLastLoc(currentLoc); mdUpdateUseLastBtn();
            m.setView([currentLoc.latitude, currentLoc.longitude], Math.max(m.getZoom() || 2, 13));
          }
        } catch {}
      });
    } else {
      copyBtn.disabled = true;
      copyBtn.style.opacity = '0.5';
      copyBtn.style.cursor = 'not-allowed';
      copyBtn.title = 'No media location available';
    }
  }

  // Cover button handlers
  overlay.querySelector('#md-event-cover-btn')?.addEventListener('click', async () => {
    if (!media.events || media.events.length === 0) {
      showWarning('This media is not associated with any event');
      return;
    }
    const eventId = media.events[0].id;
    try {
      await api.setEventCover(eventId, media.accessKey);
      showSuccess('Successfully set as event cover');
    } catch (e) {
      console.error('Failed to set event cover:', e);
      showError('Failed to set as event cover');
    }
  });

  overlay.querySelector('#md-owner-cover-btn')?.addEventListener('click', async () => {
    if (!media.original || !media.original.storeId) {
      showWarning('This media is not associated with any store');
      return;
    }
    try {
      const store = await api.getStore(media.original.storeId);
      if (!store || !store.ownerId) {
        showWarning('This media is not associated with any owner');
        return;
      }
      await api.setOwnerCover(store.ownerId, media.accessKey);
      showSuccess('Successfully set as owner cover');
    } catch (e) {
      console.error('Failed to set owner cover:', e);
      showError('Failed to set as owner cover');
    }
  });

  // Save handler
  overlay.querySelector('button.save')?.addEventListener('click', async () => {
    const description = overlay.querySelector('#md-desc').value.trim();
    const tsLocal = overlay.querySelector('#md-ts').value;
    const shootDateTime = fromLocalInputValue(tsLocal);
    const body = { starred: !!media.starred };
    if (description) body.description = description;
    if (shootDateTime) body.shootDateTime = shootDateTime;
    body.keywords = Array.isArray(keywords) ? keywords : [];
    if (clearedUserLoc) body.userDefinedLocation = null; else if (currentLoc && typeof currentLoc.latitude === 'number' && typeof currentLoc.longitude === 'number') body.userDefinedLocation = currentLoc;
    try {
      await api.updateMedia(media.accessKey, body);
      close();
      const updated = await api.getMediaByKey(media.accessKey);
      showMedia(updated);
    } catch (e) {
      showError('Failed to save media');
    }
  });
}

function initViewerControls() {
  $('#btn-first').addEventListener('click', () => loadMedia('first'));
  $('#btn-last').addEventListener('click', () => loadMedia('last'));
  $('#btn-random').addEventListener('click', () => loadMedia('random'));
  $('#btn-prev').addEventListener('click', () => currentMedia && loadMedia('previous', currentMedia.accessKey));
  $('#btn-next').addEventListener('click', () => currentMedia && loadMedia('next', currentMedia.accessKey));
  $('#btn-fullscreen').addEventListener('click', () => {
    const cont = document.querySelector('.image-container');
    if (!document.fullscreenElement) cont.requestFullscreen?.(); else document.exitFullscreen?.();
  });
  // Click zones on the image to navigate (left 1/4 prev, right 1/4 next, middle random)
  const imgContainer = document.querySelector('.image-container');
  let clickTimer = null;
  imgContainer?.addEventListener('click', (ev) => {
    // Delay to differentiate from double-click
    if (clickTimer) clearTimeout(clickTimer);
    clickTimer = setTimeout(() => {
      clickTimer = null;
      const rect = imgContainer.getBoundingClientRect();
      const x = (ev.clientX ?? 0) - rect.left;
      const ratio = rect.width > 0 ? x / rect.width : 0.5;
      if (ratio <= 0.25) {
        // Left quarter → previous
        if (currentMedia) loadMedia('previous', currentMedia.accessKey); else loadMedia('last');
      } else if (ratio >= 0.75) {
        // Right quarter → next
        if (currentMedia) loadMedia('next', currentMedia.accessKey); else loadMedia('first');
      } else {
        // Middle → random
        loadMedia('random');
      }
    }, 220);
  });
  // Toggle fullscreen on double click on the image/container
  imgContainer?.addEventListener('dblclick', () => {
    // Cancel pending single-click action
    if (clickTimer) { clearTimeout(clickTimer); clickTimer = null; }
    const cont = document.querySelector('.image-container');
    if (!document.fullscreenElement) cont.requestFullscreen?.(); else document.exitFullscreen?.();
  });

  // Slideshow helpers using setTimeout so new settings are applied at each tick
  function stopSlideshow() {
    slideshowPlaying = false;
    if (slideshowTimer) { clearTimeout(slideshowTimer); slideshowTimer = null; }
    const btn = $('#btn-play'); if (btn) btn.textContent = '▷';
  }
  function scheduleNextTick() {
    if (!slideshowPlaying) return;
    const durActive = document.querySelector('#ss-duration button.active') || document.querySelector('#ss-duration button[data-secs="20"]');
    const secs = parseInt(durActive?.dataset?.secs || '20', 10) || 20;
    const modeActive = document.querySelector('#ss-mode button.active') || document.querySelector('#ss-mode button[data-mode="next"]');
    const mode = modeActive?.dataset?.mode || 'next';
    const delay = secs * 1000;
    slideshowTimer = setTimeout(async () => {
      try {
        if (mode === 'random') {
          await loadMedia('random');
        } else {
          if (currentMedia) {
            await loadMedia('next', currentMedia.accessKey);
          } else {
            await loadMedia('first');
          }
        }
      } finally {
        if (slideshowPlaying) scheduleNextTick();
      }
    }, delay);
  }

  $('#btn-play').addEventListener('click', () => {
    const btn = $('#btn-play');
    if (slideshowPlaying) { stopSlideshow(); return; }
    slideshowPlaying = true; btn.textContent = '❚❚';
    // Start waiting according to current selection, then advance
    scheduleNextTick();
  });

  // Initialize and wire visual slideshow controls (duration and mode)
  const durGroup = document.getElementById('ss-duration');
  const modeGroup = document.getElementById('ss-mode');
  function setActive(group, btn) {
    if (!group || !btn) return;
    group.querySelectorAll('button').forEach(b => {
      const on = b === btn;
      b.classList.toggle('active', on);
      b.setAttribute('aria-pressed', on ? 'true' : 'false');
    });
  }
  // Restore last choices from localStorage
  let savedSecs = 20; let savedMode = 'next';
  try {
    const s = parseInt(localStorage.getItem('ui.slideshow.secs') || '20', 10); if (!Number.isNaN(s)) savedSecs = s;
    const m = localStorage.getItem('ui.slideshow.mode'); if (m === 'random' || m === 'next') savedMode = m;
  } catch {}
  const initialDurBtn = durGroup?.querySelector(`button[data-secs="${savedSecs}"]`) || durGroup?.querySelector('button[data-secs="20"]');
  if (durGroup && initialDurBtn) setActive(durGroup, initialDurBtn);
  const initialModeBtn = modeGroup?.querySelector(`button[data-mode="${savedMode}"]`) || modeGroup?.querySelector('button[data-mode="next"]');
  if (modeGroup && initialModeBtn) setActive(modeGroup, initialModeBtn);
  function onConfigChanged() {
    // Persist
    try {
      const da = durGroup?.querySelector('button.active');
      const ma = modeGroup?.querySelector('button.active');
      if (da?.dataset?.secs) localStorage.setItem('ui.slideshow.secs', String(parseInt(da.dataset.secs, 10) || 20));
      if (ma?.dataset?.mode) localStorage.setItem('ui.slideshow.mode', ma.dataset.mode);
    } catch {}
    // If playing, reschedule immediately
    if (!slideshowPlaying) return;
    if (slideshowTimer) { clearTimeout(slideshowTimer); slideshowTimer = null; }
    scheduleNextTick();
  }
  durGroup?.querySelectorAll('button').forEach(b => b.addEventListener('click', () => { setActive(durGroup, b); onConfigChanged(); }));
  modeGroup?.querySelectorAll('button').forEach(b => b.addEventListener('click', () => { setActive(modeGroup, b); onConfigChanged(); }));

  // Keyboard navigation for Image tab
  document.addEventListener('keydown', (e) => {
    const viewerActive = document.getElementById('tab-viewer')?.classList.contains('active');
    if (!viewerActive) return;
    const t = e.target;
    if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.tagName === 'SELECT' || t.isContentEditable)) return;
    let handled = false;
    switch (e.key) {
      case 'Home':
        loadMedia('first'); handled = true; break;
      case 'End':
        loadMedia('last'); handled = true; break;
      case 'PageDown':
        if (currentMedia) loadMedia('next', currentMedia.accessKey); else loadMedia('first');
        handled = true; break;
      case 'PageUp':
        if (currentMedia) loadMedia('previous', currentMedia.accessKey); else loadMedia('last');
        handled = true; break;
    }
    if (handled) { e.preventDefault(); e.stopPropagation(); }
  });
}

// Leaflet map
let map = null; let cluster = null; let mapLoaded = false; let mapLoading = false; const mapAddedKeys = new Set(); const mapMarkersByKey = new Map(); let pendingFocusKey = null;
function ensureMap() {
  if (mapLoaded) return;
  mapLoaded = true;
  map = L.map('map').setView([20, 0], 2);
  L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', { attribution: '&copy; OpenStreetMap' }).addTo(map);
  cluster = L.markerClusterGroup();
  map.addLayer(cluster);
  // Ensure correct sizing shortly after creation and on window resize
  setTimeout(() => { try { map.invalidateSize(true); } catch {} }, 0);
  window.addEventListener('resize', () => { try { if (map) map.invalidateSize(true); } catch {} });
  // Wire refresh button
  const refreshBtn = document.getElementById('refresh-map');
  if (refreshBtn) refreshBtn.addEventListener('click', () => loadMapData({ clear: false }));
  // Initial load
  loadMapData({ clear: false });
}

function goToWorldLocation(loc, accessKey) {
  try {
    setActiveTab('world');
    ensureMap();
    pendingFocusKey = accessKey || null;
    setTimeout(() => {
      try {
        if (map) { map.setView([loc.latitude, loc.longitude], 16); }
        if (accessKey && mapMarkersByKey.has(accessKey)) {
          const marker = mapMarkersByKey.get(accessKey);
          if (cluster && marker) {
            cluster.zoomToShowLayer(marker, () => { marker.openPopup(); });
          } else {
            marker?.openPopup?.();
          }
        } else {
          if (!mapLoading) loadMapData({ clear: false });
        }
      } catch {}
    }, 0);
  } catch {}
}

function loadMapData({ clear = false } = {}) {
  if (!map) return;
  if (mapLoading) { $('#map-status').textContent = 'Already loading…'; return; }
  if (clear) { cluster.clearLayers(); mapAddedKeys.clear(); }
  $('#map-status').textContent = 'Loading medias with location…';
  mapLoading = true;
  let received = 0;
  api.mediasWithLocations(m => {
    const loc = m.location || m.userDefinedLocation || m.deductedLocation; if (!loc) return;
    if (mapAddedKeys.has(m.accessKey)) return; // keep existing data, avoid duplicates
    mapAddedKeys.add(m.accessKey);
    const marker = L.marker([loc.latitude, loc.longitude]);
    const thumbUrl = api.mediaNormalizedUrl(m.accessKey);
    const date = m.shootDateTime || m.original?.cameraShootDateTime || '';
    const eventName = (m.events && m.events.length > 0) ? m.events[0].name : '';
    const starred = m.starred ? '⭐' : '☆';
    marker.bindPopup(`
        <div style="min-width:200px">
          <div style="font-weight:600">${eventName} ${starred}</div>
          <div style="font-size:12px;color:#555">${date ? new Date(date).toLocaleString() : ''}</div>
          <img src="${thumbUrl}" alt="media" style="width:100%;height:auto;border-radius:6px;margin-top:6px"/>
          <button id="goto-${m.accessKey}" style="margin-top:6px">Open</button>
        </div>
      `);
    marker.on('popupopen', () => {
      const b = document.getElementById(`goto-${m.accessKey}`);
      if (b) b.onclick = () => { setActiveTab('viewer'); showMedia(m); };
    });
    mapMarkersByKey.set(m.accessKey, marker);
    cluster.addLayer(marker);
    if (pendingFocusKey && m.accessKey === pendingFocusKey) {
      try { cluster.zoomToShowLayer(marker, () => { marker.openPopup(); }); } catch {}
      pendingFocusKey = null;
    }
    received += 1; if (received % 200 === 0) $('#map-status').textContent = `Loaded ${mapAddedKeys.size} markers…`;
  }).then(() => { mapLoading = false; $('#map-status').textContent = `Done. Markers: ${mapAddedKeys.size}`; }).catch(() => { mapLoading = false; $('#map-status').textContent = `Load interrupted. Markers: ${mapAddedKeys.size}`; });
}

// Events
// Helpers for Event edit modal
function toLocalInputValue(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  if (isNaN(d.getTime())) return '';
  const pad = n => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth()+1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}
function fromLocalInputValue(val) {
  if (!val) return undefined;
  const d = new Date(val);
  if (isNaN(d.getTime())) return undefined;
  return d.toISOString();
}

function refreshEventTile(ev) {
  try {
    const list = document.getElementById('events-list');
    if (!list) return;
    const li = list.querySelector(`li[data-event-id="${ev.id}"]`);
    if (!li) return;
    // Update stored event ref
    li.__event = ev;
    // Update title
    const titleEl = li.querySelector('h4');
    if (titleEl) titleEl.textContent = ev.name || '(no name)';
    // Update timestamp + location pin
    const tsEl = li.querySelector('.ev-ts');
    if (tsEl) {
      const tsStr = ev.timestamp ? new Date(ev.timestamp).toLocaleString() : '';
      const pinSvgGreen = `<svg xmlns="http://www.w3.org/2000/svg" width="1em" height="1em" viewBox="0 0 24 24" fill="#10b981" style="vertical-align:-0.15em;margin-right:6px"><path d="M12 2c-3.314 0-6 2.686-6 6 0 5 6 12 6 12s6-7 6-12c0-3.314-2.686-6-6-6zm0 10a4 4 0 110-8 4 4 0 010 8z"/></svg>`;
      const tsWithPin = (ev.location ? pinSvgGreen + ' ' : '') + tsStr;
      tsEl.innerHTML = tsWithPin;
    }
    // Update edit button handler to use fresh event data
    const editBtn = li.querySelector('.ev-edit-btn');
    if (editBtn) {
      editBtn.onclick = (e) => { e.stopPropagation(); openEventEditModal(ev); };
    }
  } catch {}
}

function openEventEditModal(ev) {
  // Prevent multiple modals open at the same time
  if (document.querySelector('.modal-overlay')) { return; }
  const overlay = document.createElement('div');
  overlay.className = 'modal-overlay';
  const nameVal = ev.name || '';
  const descVal = ev.description || '';
  const tsVal = toLocalInputValue(ev.timestamp);
  const overlayHtml = `
    <div class="modal">
      <header>
        <div>Edit event</div>
        <button class="close" title="Close" style="background:none;border:none;font-size:18px;cursor:pointer">✕</button>
      </header>
      <div class="content">
        <div class="row">
          <div>
            <label>Name</label>
            <input type="text" id="ev-name" value="${nameVal.replace(/&/g,'&amp;').replace(/"/g,'&quot;')}">
            <label style="margin-top:8px">Description</label>
            <textarea id="ev-desc">${(descVal||'').replace(/&/g,'&amp;').replace(/</g,'&lt;')}</textarea>
            <label style="margin-top:8px">Timestamp</label>
            <input type="datetime-local" id="ev-ts" value="${tsVal}">
            <label style="margin-top:8px">Keywords</label>
            <div class="chips" id="ev-chips"></div>
          </div>
          <div>
            <label>Location</label>
            <div style="margin:4px 0 6px 0; display:flex; gap:6px; flex-wrap:wrap; align-items:center">
              <button id="ev-remember-loc" type="button" title="Remember current selected location for later reuse" style="background:#f3f4f6;border:1px solid #e5e7eb;padding:4px 8px;border-radius:6px;cursor:pointer">Remember selection</button>
              <button id="ev-use-last-loc" type="button" title="Use last selected location" style="background:#f3f4f6;border:1px solid #e5e7eb;padding:4px 8px;border-radius:6px;cursor:pointer">Use last selection</button>
              <button id="ev-reset-loc" type="button" title="Remove event location" style="background:#fee2e2;border:1px solid #fecaca;padding:4px 8px;border-radius:6px;cursor:pointer">Reset location</button>
            </div>
            <div id="event-edit-map"></div>
          </div>
        </div>
      </div>
      <footer>
        <button type="button" class="cancel">Cancel</button>
        <button type="button" class="save" style="background:#2563eb;color:#fff;border:1px solid #2563eb;padding:6px 10px;border-radius:6px;cursor:pointer">Save</button>
      </footer>
    </div>`;
  overlay.innerHTML = overlayHtml;
  document.body.appendChild(overlay);

  // Accessibility and focus management
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

  // Keywords chips
  const chipsEl = overlay.querySelector('#ev-chips');
  let keywords = Array.isArray(ev.keywords) ? [...ev.keywords] : [];
  const input = document.createElement('input');
  input.type = 'text';
  input.placeholder = 'Add keyword and press Enter';
  function renderChips() {
    chipsEl.innerHTML = '';
    for (const kw of keywords) {
      const chip = document.createElement('span');
      chip.className = 'chip';
      chip.textContent = kw;
      const rm = document.createElement('button');
      rm.className = 'remove'; rm.type = 'button'; rm.textContent = '×';
      rm.addEventListener('click', () => { keywords = keywords.filter(k => k !== kw); renderChips(); });
      chip.appendChild(rm);
      chipsEl.appendChild(chip);
    }
    chipsEl.appendChild(input);
  }
  function addKeywordFromInput() {
    const val = input.value.trim();
    if (!val) return; if (!keywords.includes(val)) keywords.push(val); input.value=''; renderChips(); input.focus();
  }
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' || e.key === ',') { e.preventDefault(); addKeywordFromInput(); }
    else if (e.key === 'Backspace' && !input.value && keywords.length > 0) { keywords.pop(); renderChips(); }
  });
  input.addEventListener('blur', () => { addKeywordFromInput(); });
  renderChips();

  // Map setup
  let currentLoc = ev.location ? { ...ev.location } : null;
  let clearedLoc = false;
  // Persisted last-selected location helpers
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
        }
        else { marker.setLatLng(latlng); }
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
      // Wire "Use last selection" and "Remember selection" buttons for event
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
    const body = { name };
    if (description) body.description = description;
    if (timestamp) body.timestamp = timestamp;
    if (clearedLoc) body.location = null; else if (currentLoc && typeof currentLoc.latitude === 'number' && typeof currentLoc.longitude === 'number') body.location = currentLoc;
    if (keywords && keywords.length > 0) body.keywords = keywords;
    try {
      await api.updateEvent(ev.id, body);
      // Build an updated event object locally (optimistic refresh)
      const updatedEv = { ...ev };
      updatedEv.name = name || ev.name;
      updatedEv.description = description || undefined;
      updatedEv.timestamp = timestamp || undefined;
      if (clearedLoc) updatedEv.location = null; else if (body.location) updatedEv.location = body.location;
      if (keywords && keywords.length >= 0) updatedEv.keywords = keywords;
      close();
      refreshEventTile(updatedEv);
    } catch {
      showError('Failed to save event');
    }
  });
}

async function goToMosaicAtTimestamp(ts) {
  try {
    setActiveTab('mosaic');
    // Wait for mosaic to initialize its timestamp range
    let tries = 0;
    while (tries < 100 && (!mosaicOldestTimestamp || !mosaicNewestTimestamp)) {
      await new Promise(r => setTimeout(r, 50));
      tries++;
    }
    if (ts) {
      await refreshMosaicAtTimestamp(ts);
    }
  } catch {}
}

async function loadEvents() {
  const list = $('#events-list'); list.innerHTML = '';
  try {
    const events = await api.listEvents();
    // Sort by timestamp desc (newest first)
    events.sort((a, b) => {
      const ta = a.timestamp ? Date.parse(a.timestamp) : 0;
      const tb = b.timestamp ? Date.parse(b.timestamp) : 0;
      return tb - ta;
    });

    // Lazy load state/images per tile when needed
    const stateCache = new Map(); // originalId -> Promise<State>
    const limit = 4; // small concurrency for state->image resolution
    let inFlight = 0;
    const pending = [];
    const scheduled = new WeakSet();

    async function resolveAndRender(li, ev) {
      inFlight++;
      try {
        if (!ev.originalId) return;
        let p = stateCache.get(ev.originalId);
        if (!p) {
          p = api.getState(ev.originalId).catch(err => { stateCache.delete(ev.originalId); throw err; });
          stateCache.set(ev.originalId, p);
        }
        const st = await p;
        if (!st || !st.mediaAccessKey) return;
        const img = new Image();
        img.src = api.mediaNormalizedUrl(st.mediaAccessKey);
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
          try { await goToMosaicAtTimestamp(ev.timestamp); } catch {}
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
      li.__event = ev; // attach ref for observer
      if (ev.id) li.dataset.eventId = ev.id;
      const tsStr = ev.timestamp ? new Date(ev.timestamp).toLocaleString() : '';
      const pinSvgGreen = `<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"1em\" height=\"1em\" viewBox=\"0 0 24 24\" fill=\"#10b981\" style=\"vertical-align:-0.15em;margin-right:6px\"><path d=\"M12 2c-3.314 0-6 2.686-6 6 0 5 6 12 6 12s6-7 6-12c0-3.314-2.686-6-6-6zm0 10a4 4 0 110-8 4 4 0 010 8z\"/></svg>`;
      const tsWithPin = (ev.location ? pinSvgGreen + ' ' : '') + tsStr;
      li.innerHTML = `
        <div class=\"ev-thumb\" style=\"width:100%;height:160px;border-radius:6px;background:#f3f4f6;display:flex;align-items:center;justify-content:center;overflow:hidden;margin-bottom:6px;color:#9ca3af;font-size:12px;\">No preview</div>
        <h4 style=\"margin:0 0 4px 0;\">${ev.name || '(no name)'}</h4>
        <div class=\"ev-ts\" style=\"font-size:12px;color:#555\">${tsWithPin}</div>
        <button class=\"ev-edit-btn\" title=\"Edit\">✎ Edit</button>
      `;
      list.appendChild(li);
      const editBtn = li.querySelector('.ev-edit-btn');
      if (editBtn) { editBtn.onclick = (e) => { e.stopPropagation(); openEventEditModal(ev); }; }

      // Clicking before image is loaded should still navigate when possible
      if (ev.originalId) {
        li.style.cursor = 'pointer';
        li.onclick = async () => {
          try {
            await goToMosaicAtTimestamp(ev.timestamp);
          } catch {}
        };
      }

      if (observer && ev.originalId) {
        observer.observe(li);
      } else if (ev.originalId) {
        pending.push({ li, ev }); schedule();
      }
    }
  } catch (e) { list.innerHTML = '<li>Failed to load events</li>'; }
}

function openEventCreateModal() {
  if (document.querySelector('.modal-overlay')) return;
  const overlay = document.createElement('div'); overlay.className = 'modal-overlay';
  overlay.innerHTML = `
    <div class="modal" role="dialog" aria-modal="true" tabindex="-1">
      <header>
        <div>Create event</div>
        <button class="close" title="Close" style="background:none;border:none;font-size:18px;cursor:pointer">✕</button>
      </header>
      <div class="content">
        <div class="row">
          <div>
            <label>Name</label>
            <input type="text" id="evc-name" value="" required>
            <label style="margin-top:8px">Description</label>
            <input type="text" id="evc-desc" value="">
            <label style="margin-top:8px">Keywords</label>
            <div class="chips" id="evc-chips"></div>
          </div>
        </div>
      </div>
      <footer>
        <button type="button" class="cancel">Cancel</button>
        <button type="button" class="save" style="background:#2563eb;color:#fff;border:1px solid #1d4ed8;border-radius:6px;padding:6px 10px;">Create</button>
      </footer>
    </div>`;
  document.body.appendChild(overlay);
  const modal = overlay.querySelector('.modal');
  const close = () => { overlay.remove(); };
  overlay.addEventListener('click', (e) => { if (e.target === overlay) close(); });
  overlay.querySelector('button.close')?.addEventListener('click', close);
  overlay.querySelector('button.cancel')?.addEventListener('click', (e)=>{ e.preventDefault(); e.stopPropagation(); close(); });
  setTimeout(()=>{ (modal.querySelector('#evc-name')||modal).focus(); }, 0);

  // Keywords chips management
  const chipsEl = overlay.querySelector('#evc-chips');
  let keywords = [];
  const input = document.createElement('input');
  input.type = 'text';
  input.placeholder = 'Add keyword and press Enter';
  function renderChips() {
    chipsEl.innerHTML = '';
    for (const kw of keywords) {
      const chip = document.createElement('span');
      chip.className = 'chip';
      chip.textContent = kw;
      const rm = document.createElement('button');
      rm.className = 'remove'; rm.type = 'button'; rm.textContent = '×';
      rm.addEventListener('click', () => { keywords = keywords.filter(k => k !== kw); renderChips(); });
      chip.appendChild(rm);
      chipsEl.appendChild(chip);
    }
    chipsEl.appendChild(input);
  }
  function addKeywordFromInput() {
    const val = input.value.trim();
    if (!val) return; if (!keywords.includes(val)) keywords.push(val); input.value=''; renderChips(); input.focus();
  }
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' || e.key === ',') { e.preventDefault(); addKeywordFromInput(); }
    else if (e.key === 'Backspace' && !input.value && keywords.length > 0) { keywords.pop(); renderChips(); }
  });
  input.addEventListener('blur', () => { addKeywordFromInput(); });
  renderChips();

  overlay.querySelector('button.save')?.addEventListener('click', async () => {
    const name = modal.querySelector('#evc-name').value.trim();
    const description = modal.querySelector('#evc-desc').value.trim();
    if (!name) { showWarning('Event name is required'); return; }
    const body = { name };
    if (description) body.description = description;
    if (keywords.length > 0) body.keywords = keywords;
    try {
      await api.createEvent(body);
      close();
      await loadEvents();
    } catch (e) {
      showError('Failed to create event');
    }
  });
}

function initEventsTab() {
  $('#refresh-events').addEventListener('click', loadEvents);
  const createBtn = document.getElementById('create-event');
  if (createBtn && !createBtn.__wired) { createBtn.addEventListener('click', () => openEventCreateModal()); createBtn.__wired = true; }
}

// Mosaic Tab - Fixed timestamp-based scroller
let mosaicOldestTimestamp = null; // Timestamp of first (oldest) media
let mosaicNewestTimestamp = null; // Timestamp of last (newest) media
let mosaicMediaCache = new Map(); // Map: mediaAccessKey -> media object (for all loaded media)
// DOM tile cache to reuse already created tiles (prevents image reloads)
let mosaicTileCache = new Map(); // Map: mediaAccessKey -> HTMLElement (tile)
// Adjacency caches to avoid redundant API calls when navigating
let mosaicNextByKey = new Map(); // Map: accessKey -> next (newer) accessKey
let mosaicPrevByKey = new Map(); // Map: accessKey -> previous (older) accessKey
// In‑flight fetch cache to de‑dupe concurrent neighbor requests
let mosaicFetchCache = new Map(); // Map: `${dir}:${accessKey}` -> Promise<media|null>
// Cache for normalized images to avoid reloading and to coordinate preloading across tiles
let mosaicNormalizedImageCache = new Map(); // Map: accessKey -> Promise<void>
let mosaicIsLoading = false;
let mosaicScrollTimeout = null;
let mosaicObserver = null;
const MOSAIC_VIRTUAL_HEIGHT = 10000; // Fixed virtual scroll height in pixels
const MOSAIC_LOAD_BUFFER = 30; // Default number of media to load around visible range
// Input mode detection for scroll behaviors
let mosaicInputMode = 'idle'; // 'idle' | 'wheel'
let mosaicLastWheelDir = null; // 'previous' | 'next'
let mosaicWheelResetTimer = null;
// Current selection state
let mosaicCurrentMedia = null;
let mosaicCurrentTimestamp = null;

// Persist/retrieve selected timestamp across reloads
function persistMosaicTimestamp(ts) {
  try { if (ts && typeof ts === 'string') localStorage.setItem('mosaic.selectedTimestamp', ts); } catch {}
}
function getPersistedMosaicTimestamp() {
  try { return localStorage.getItem('mosaic.selectedTimestamp'); } catch { return null; }
}

// Compute mosaic layout based on current size to know how many tiles we need to fill viewport
function computeMosaicLayout() {
  const tabSection = document.getElementById('tab-mosaic');
  const container = document.getElementById('mosaic-container');
  const spacer = document.getElementById('mosaic-scroll-spacer');
  const rootGapRem = 0.5; // matches CSS gap: 0.5rem
  const rem = parseFloat(getComputedStyle(document.documentElement).fontSize) || 16;
  const gapPx = rootGapRem * rem;
  const width = (spacer || container || tabSection)?.clientWidth || window.innerWidth;
  const minTile = 200; // as in CSS minmax(200px, 1fr)
  const columns = Math.max(1, Math.floor((width + gapPx) / (minTile + gapPx)));
  // Approximate tile size using available width
  const tileSize = Math.max(minTile, Math.floor((width - (columns - 1) * gapPx) / columns));
  const heightEl = container || tabSection;
  const viewportH = heightEl?.clientHeight || window.innerHeight;
  const viewportRows = Math.max(1, Math.ceil(viewportH / tileSize));
  const bufferRows = 2; // keep a little buffer for smoothness
  const totalNeeded = columns * (viewportRows + bufferRows);
  return { columns, tileSize, viewportRows, bufferRows, totalNeeded };
}

// Logarithmic scale mapping: scroll position (0=top/newest to 1=bottom/oldest) <-> timestamp
// Recent years get more scroll space than older years
function scrollPositionToTimestamp(scrollRatio, oldestTime, newestTime) {
  if (!oldestTime || !newestTime) return null;
  
  const oldestMs = new Date(oldestTime).getTime();
  const newestMs = new Date(newestTime).getTime();
  const totalRange = newestMs - oldestMs;
  
  if (totalRange <= 0) return new Date(newestMs).toISOString();
  
  // Use logarithmic scale: more recent = more space
  // scrollRatio 0 (top) = newest, scrollRatio 1 (bottom) = oldest
  // Apply exponential decay: timestamp = newest - range * (e^(k*scrollRatio) - 1) / (e^k - 1)
  const k = 3; // Exponential factor (higher = more emphasis on recent)
  const normalizedPosition = (Math.exp(k * scrollRatio) - 1) / (Math.exp(k) - 1);
  const targetMs = newestMs - totalRange * normalizedPosition;
  
  return new Date(targetMs).toISOString();
}

function timestampToScrollPosition(timestamp, oldestTime, newestTime) {
  if (!oldestTime || !newestTime || !timestamp) return 0;
  
  const oldestMs = new Date(oldestTime).getTime();
  const newestMs = new Date(newestTime).getTime();
  const targetMs = new Date(timestamp).getTime();
  const totalRange = newestMs - oldestMs;
  
  if (totalRange <= 0) return 0;
  
  const normalizedPosition = (newestMs - targetMs) / totalRange;
  
  // Inverse of logarithmic scale
  const k = 3;
  const scrollRatio = Math.log(normalizedPosition * (Math.exp(k) - 1) + 1) / k;
  
  return Math.max(0, Math.min(1, scrollRatio));
}

// Helper: get a media timestamp string with fallbacks
function mediaTimestamp(media) {
  try {
    return media?.shootDateTime || media?.original?.cameraShootDateTime || null;
  } catch { return null; }
}

// Helper: get media timestamp in ms, with optional fallback (e.g., current context)
function mediaTimestampMs(media, fallbackMs = null) {
  const ts = mediaTimestamp(media);
  if (!ts) return null;
  const ms = Date.parse(ts);
  if (!Number.isNaN(ms)) return ms;
  return null;
}

// Check if a timestamp string is valid
function isValidTimestampStr(ts) {
  if (!ts) return false;
  const d = new Date(ts);
  return !Number.isNaN(d.getTime());
}

// Check if a media has a valid timestamp
function isMediaDated(media) {
  const ts = mediaTimestamp(media);
  return isValidTimestampStr(ts);
}

// ---------------- Cache-aware helpers for mosaic navigation and rendering ----------------
// Link adjacency between two medias where olderMedia is chronologically older than newerMedia
function linkAdjacency(olderMedia, newerMedia) {
  try {
    if (!olderMedia || !newerMedia) return;
    const ok = olderMedia.accessKey; const nk = newerMedia.accessKey;
    if (!ok || !nk || ok === nk) return;
    mosaicNextByKey.set(ok, nk);
    mosaicPrevByKey.set(nk, ok);
  } catch {}
}

// Link a sequential list ordered from oldest -> newest
function linkSequentialAscOldestToNewest(list) {
  try {
    if (!Array.isArray(list)) return;
    for (let i = 0; i < list.length - 1; i++) {
      const a = list[i];
      const b = list[i + 1];
      if (a && b && a.accessKey && b.accessKey) linkAdjacency(a, b);
    }
  } catch {}
}

function getAdjacentFromCache(media, direction) {
  try {
    if (!media || !media.accessKey) return null;
    const key = media.accessKey;
    const dir = direction === 'next' ? 'next' : 'previous';
    const adjKey = dir === 'next' ? mosaicNextByKey.get(key) : mosaicPrevByKey.get(key);
    if (!adjKey) return null;
    const adj = mosaicMediaCache.get(adjKey);
    return adj || null;
  } catch { return null; }
}

async function fetchAdjacent(media, direction) {
  try {
    if (!media || !media.accessKey) return null;
    const dir = direction === 'next' ? 'next' : 'previous';
    // If we already know the neighbor, return it
    const known = getAdjacentFromCache(media, dir);
    if (known) return known;
    const cacheKey = `${dir}:${media.accessKey}`;
    if (mosaicFetchCache.has(cacheKey)) {
      return await mosaicFetchCache.get(cacheKey);
    }
    const p = api.getMedia(dir, media.accessKey)
      .then((m) => {
        if (!m || !m.accessKey || m.accessKey === media.accessKey) return null;
        try {
          mosaicMediaCache.set(m.accessKey, m);
          if (dir === 'previous') { // fetched older than current
            linkAdjacency(m, media);
          } else { // fetched newer than current
            linkAdjacency(media, m);
          }
        } catch {}
        return m;
      })
      .catch((err) => { throw err; })
      .finally(() => { try { mosaicFetchCache.delete(cacheKey); } catch {} });
    mosaicFetchCache.set(cacheKey, p);
    return await p;
  } catch { return null; }
}

// Ensure the cache window around a center media is filled enough to render the viewport
async function ensureCacheWindowAround(centerMedia, count = MOSAIC_LOAD_BUFFER, biasOlder = 0.5) {
  const res = { before: [], after: [], loaded: [] };
  try {
    if (!centerMedia || !centerMedia.accessKey) return res;

    const refIsValid = isMediaDated(centerMedia);
    const wantBefore = Math.max(0, Math.floor(count * Math.max(0, Math.min(1, biasOlder))))
    const wantAfter = Math.max(0, count - wantBefore - (refIsValid ? 1 : 0));

    // First traverse cached neighbors on the older side
    let mPrev = centerMedia; let guardPrev = 0;
    while (res.before.length < wantBefore && guardPrev < count * 2) {
      guardPrev++;
      const neighbor = getAdjacentFromCache(mPrev, 'previous');
      if (!neighbor) break;
      mPrev = neighbor;
      if (isMediaDated(neighbor)) res.before.push(neighbor);
    }
    // Fetch remaining older side
    while (res.before.length < wantBefore && guardPrev < count * 4) {
      guardPrev++;
      const neighbor = await fetchAdjacent(mPrev, 'previous');
      if (!neighbor) break;
      mPrev = neighbor;
      if (isMediaDated(neighbor)) res.before.push(neighbor);
    }

    // Then the newer side
    let mNext = centerMedia; let guardNext = 0;
    while (res.after.length < wantAfter && guardNext < count * 2) {
      guardNext++;
      const neighbor = getAdjacentFromCache(mNext, 'next');
      if (!neighbor) break;
      mNext = neighbor;
      if (isMediaDated(neighbor)) res.after.push(neighbor);
    }
    while (res.after.length < wantAfter && guardNext < count * 4) {
      guardNext++;
      const neighbor = await fetchAdjacent(mNext, 'next');
      if (!neighbor) break;
      mNext = neighbor;
      if (isMediaDated(neighbor)) res.after.push(neighbor);
    }

    // Build final ordered list oldest -> newest
    const seq = [];
    for (let i = res.before.length - 1; i >= 0; i--) seq.push(res.before[i]);
    if (refIsValid) seq.push(centerMedia);
    for (const n of res.after) seq.push(n);

    try { linkSequentialAscOldestToNewest(seq); } catch {}
    for (const media of seq) { if (media && media.accessKey) mosaicMediaCache.set(media.accessKey, media); }

    res.loaded = seq;
  } catch {}
  return res;
}

// Helper: Create a tile element for a media
function createMosaicTile(media) {
  // Reuse existing tile if already created
  try {
    if (media && media.accessKey) {
      const existing = mosaicTileCache.get(media.accessKey);
      if (existing) return existing;
    }
  } catch {}

  const tile = document.createElement('div');
  tile.className = 'mosaic-tile';
  tile.dataset.mediaKey = media.accessKey;
  tile.__media = media;
  
  const tsStr = mediaTimestamp(media);
  if (tsStr) {
    tile.dataset.timestamp = tsStr;
    // Native tooltip on hover with precise timestamp
    try {
      const d = new Date(tsStr);
      if (!Number.isNaN(d.getTime())) {
        tile.title = d.toLocaleString(undefined, {
          year: 'numeric', month: 'short', day: 'numeric',
          hour: '2-digit', minute: '2-digit', second: '2-digit'
        });
      }
    } catch {}
  }
  
  tile.onclick = async () => {
    try {
      // Prefer cached media info to avoid extra API calls
      const cached = (media && media.accessKey) ? (mosaicMediaCache.get(media.accessKey) || tile.__media) : null;
      const fullMedia = (cached && cached.accessKey) ? cached : await api.getMediaByKey(media.accessKey);
      setActiveTab('viewer');
      showMedia(fullMedia);
    } catch (e) {
      console.warn('Failed to load media:', e);
    }
  };
  
  // Layered images: miniature below, normalized above fades in after fully decoded (no white flash)
  const miniatureUrl = api.mediaMiniatureUrl(media.accessKey);
  const normalizedUrl = api.mediaNormalizedUrl(media.accessKey);

  const miniImg = new Image();
  miniImg.className = 'layer-mini';
  const altTs = mediaTimestamp(media);
  miniImg.alt = altTs ? new Date(altTs).toLocaleDateString() : '';
  miniImg.loading = 'lazy';
  miniImg.decoding = 'async';
  miniImg.src = miniatureUrl;
  tile.appendChild(miniImg);

  const hiImg = new Image();
  hiImg.className = 'layer-hi';
  hiImg.alt = '';
  hiImg.loading = 'eager';
  hiImg.decoding = 'async';
  // Do not set src yet; we will assign it once preload completes
  tile.appendChild(hiImg);

  async function showNormalized() {
    try {
      if (tile.__normalizedLoaded) {
        // Already loaded once, just fade in
        hiImg.style.opacity = '1';
        return;
      }
      let p = mosaicNormalizedImageCache.get(media.accessKey);
      if (!p) {
        p = new Promise((resolve) => {
          const preload = new Image();
          preload.decoding = 'async';
          preload.onload = () => resolve();
          preload.src = normalizedUrl;
        });
        mosaicNormalizedImageCache.set(media.accessKey, p);
      }
      await p;
      if (!hiImg.src) hiImg.src = normalizedUrl;
      // Ensure decoding is complete before making it visible
      try { if (hiImg.decode) await hiImg.decode(); } catch {}
      tile.__normalizedLoaded = true;
      hiImg.style.opacity = '1';
    } catch {}
  }
  function hideNormalized() {
    try { if (!tile.__normalizedLoaded) { hiImg.style.opacity = '0'; } } catch {}
  }
  tile.addEventListener('mouseenter', showNormalized, { passive: true });
  tile.addEventListener('mouseleave', hideNormalized, { passive: true });
  // Also upgrade on touchstart for touch devices
  tile.addEventListener('touchstart', showNormalized, { passive: true });

  // Cache created tile for reuse in subsequent renders
  try { if (media && media.accessKey) mosaicTileCache.set(media.accessKey, tile); } catch {}
  
  return tile;
}

// Helper: Load media around a target timestamp
async function loadMediaAroundTimestamp(targetTimestamp, count = MOSAIC_LOAD_BUFFER) {
  const result = { referenceMedia: null, loaded: [] };
  try {
    // Use the new referenceMediaTimestamp parameter to get media near target timestamp
    // Try 'next' first, but if we get 404 (beyond last photo), try 'previous' instead
    let referenceMedia = null;
    try {
      referenceMedia = await api.getMedia('next', null, targetTimestamp);
    } catch (e) {
      // If 'next' fails (404 - no next photo exists), try 'previous' instead
      // This happens when scrolling beyond the newest photos
      try {
        referenceMedia = await api.getMedia('previous', null, targetTimestamp);
      } catch (e2) {
        // Both directions failed, return empty
        console.warn('Could not find media near timestamp:', targetTimestamp);
        return result;
      }
    }
    if (!referenceMedia) return result;
    result.referenceMedia = referenceMedia;

    // Ensure we fill the window around the reference, reusing cache first and only fetching missing neighbors
    const biasOlder = 0.5; // neutral split when jumping from timeline
    const ensured = await ensureCacheWindowAround(referenceMedia, count, biasOlder);
    result.loaded = ensured.loaded;

    // Cache already updated by ensureCacheWindowAround via fetchAdjacent; still make sure all are cached
    for (const media of result.loaded) {
      if (media && media.accessKey) {
        mosaicMediaCache.set(media.accessKey, media);
      }
    }
  } catch (e) {
    console.warn('Error loading media around timestamp:', e);
  }
  return result;
}

// Helper: Load media using a single directional lookup from a reference timestamp
async function loadMediaDirectionalFromTimestamp(targetTimestamp, direction = 'previous', count = MOSAIC_LOAD_BUFFER) {
  const result = { referenceMedia: null, loaded: [] };
  const dir = direction === 'next' ? 'next' : 'previous';
  try {
    // Only use the provided direction from the reference timestamp
    let referenceMedia = null;
    try {
      referenceMedia = await api.getMedia(dir, null, targetTimestamp);
    } catch (e) {
      // Do not fallback to the opposite direction for wheel-based navigation
      return result;
    }
    if (!referenceMedia) return result;
    result.referenceMedia = referenceMedia;

    // Prefer cache and only fetch missing neighbors around the reference
    const biasOlder = dir === 'previous' ? 0.65 : 0.35;
    const ensured = await ensureCacheWindowAround(referenceMedia, count, biasOlder);
    result.loaded = ensured.loaded;

    for (const media of result.loaded) {
      if (media && media.accessKey) mosaicMediaCache.set(media.accessKey, media);
    }
  } catch (e) {
    console.warn('Error in loadMediaDirectionalFromTimestamp:', e);
  }
  return result;
}

// Helper: Render mosaic tiles in sorted chronological order (newest to oldest)
function renderMosaicTiles(currentTimestamp, columns, viewportRows, totalNeeded, referenceMedia) {
  const spacer = document.getElementById('mosaic-scroll-spacer');
  const tabSection = document.getElementById('tab-mosaic');
  if (!spacer || !tabSection) return;

  const layout = computeMosaicLayout();
  if (!columns) columns = layout.columns;
  if (!viewportRows) viewportRows = layout.viewportRows;
  if (!totalNeeded) totalNeeded = layout.totalNeeded;

  // Build a sorted list (newest first) from cache, with timestamp fallbacks
  const currentMs = Date.parse(currentTimestamp);
  const items = [];
  for (const media of mosaicMediaCache.values()) {
    const ms = mediaTimestampMs(media);
    if (ms == null) continue; // Ignore photos with invalid or missing date
    items.push({ media, ms });
  }
  items.sort((a, b) => b.ms - a.ms);

  // Locate the center index: prefer the exact reference media if provided
  let centerIndex = 0;
  if (referenceMedia && referenceMedia.accessKey) {
    const idx = items.findIndex(it => it.media.accessKey === referenceMedia.accessKey);
    if (idx >= 0) centerIndex = idx; else {
      // Fallback: nearest by timestamp
      let best = 0, bestDelta = Number.POSITIVE_INFINITY;
      for (let i = 0; i < items.length; i++) {
        const d = Math.abs(items[i].ms - currentMs);
        if (d < bestDelta) { bestDelta = d; best = i; }
      }
      centerIndex = best;
    }
  } else {
    // Nearest by timestamp
    let best = 0, bestDelta = Number.POSITIVE_INFINITY;
    for (let i = 0; i < items.length; i++) {
      const d = Math.abs(items[i].ms - currentMs);
      if (d < bestDelta) { bestDelta = d; best = i; }
    }
    centerIndex = best;
  }

  // Determine slice to display (enough to fill viewport + buffer)
  let startIndex = Math.max(0, centerIndex - Math.floor(totalNeeded / 2));
  let endIndex = Math.min(items.length, startIndex + totalNeeded);
  startIndex = Math.max(0, endIndex - totalNeeded);
  const display = items.slice(startIndex, endIndex).map(it => it.media);

  // Ensure spacer structure: a top pad for vertical offset and a grid for tiles
  let topPad = spacer.querySelector('.mosaic-top-pad');
  let grid = spacer.querySelector('.mosaic-grid');
  if (!topPad) {
    topPad = document.createElement('div');
    topPad.className = 'mosaic-top-pad';
    topPad.style.width = '100%';
    topPad.style.height = '0px';
    spacer.appendChild(topPad);
  }
  if (!grid) {
    grid = document.createElement('div');
    grid.className = 'mosaic-grid';
    spacer.appendChild(grid);
  }

  // Rebuild grid content using cached tiles to prevent reloads
  const nodes = [];
  for (const media of display) {
    nodes.push(createMosaicTile(media));
  }
  try { grid.replaceChildren(...nodes); } catch { grid.innerHTML = ''; nodes.forEach(n => grid.appendChild(n)); }

  // Compute pixel offset to center the reference row at the current scroll position
  const rem = parseFloat(getComputedStyle(document.documentElement).fontSize) || 16;
  const gapPx = 0.5 * rem; // must match CSS gap
  const rowHeight = layout.tileSize + gapPx;
  const centerLocalIndex = centerIndex - startIndex;
  const centerRow = Math.floor(centerLocalIndex / columns);
  const rowsCount = Math.ceil(display.length / columns);
  const gridHeight = Math.max(0, rowsCount * rowHeight - gapPx);
  const containerEl = document.getElementById('mosaic-container');
  const containerH = (containerEl?.clientHeight || tabSection.clientHeight) || 0;
  const desiredCenterY = containerH / 2;

  // Desired offset to center the reference row. Can be negative when grid is taller than viewport.
  let offset = desiredCenterY - (centerRow + 0.5) * rowHeight;

  // Clamp offset so the grid remains fully visible within the mosaic viewport.
  // When grid is taller than viewport, minOffset is negative and allows revealing the last rows.
  const minOffset = Math.min(0, containerH - gridHeight);
  const maxOffset = 0;
  offset = Math.max(minOffset, Math.min(maxOffset, offset));

  // Apply using either top padding (for positive offset) or a negative translate (for negative offset).
  if (offset >= 0) {
    topPad.style.height = `${Math.floor(offset)}px`;
    if (grid && grid.style) grid.style.transform = 'translateY(0px)';
  } else {
    topPad.style.height = '0px';
    if (grid && grid.style) grid.style.transform = `translateY(${Math.floor(offset)}px)`;
  }
}

// Build the left-side clickable timeline with year markers
function buildMosaicTimeline() {
  try {
    const tl = document.getElementById('mosaic-timeline');
    if (!tl) return;
    tl.innerHTML = '';
    // Cursor element
    const cursor = document.createElement('div');
    cursor.className = 'cursor';
    cursor.style.top = '0%';
    tl.appendChild(cursor);
    // Year lines and labels
    if (!mosaicOldestTimestamp || !mosaicNewestTimestamp) return;
    const oldest = new Date(mosaicOldestTimestamp);
    const newest = new Date(mosaicNewestTimestamp);
    if (!(oldest instanceof Date) || isNaN(oldest.getTime())) return;
    if (!(newest instanceof Date) || isNaN(newest.getTime())) return;
    const startYear = newest.getUTCFullYear();
    const endYear = oldest.getUTCFullYear();
    const rect = tl.getBoundingClientRect();
    const height = rect.height || tl.clientHeight || 0;
    const fontSize = parseFloat(getComputedStyle(tl).fontSize) || 10;
    const minGap = Math.max(12, Math.floor(fontSize * 1.6));
    let lastLabelYPx = -Infinity;
    for (let y = startYear; y >= endYear; y--) {
      const ts = new Date(Date.UTC(y, 6, 1)).toISOString();
      const ratio = timestampToScrollPosition(ts, mosaicOldestTimestamp, mosaicNewestTimestamp);
      const topPct = Math.max(0, Math.min(100, ratio * 100));
      const line = document.createElement('div');
      line.className = 'year-line';
      line.style.top = `${topPct}%`;
      tl.appendChild(line);
      const yPx = (topPct / 100) * height;
      if (yPx - lastLabelYPx >= minGap) {
        const label = document.createElement('div');
        label.className = 'year-label';
        label.style.top = `${topPct}%`;
        label.textContent = String(y);
        tl.appendChild(label);
        lastLabelYPx = yPx;
      }
    }
    // Tooltip for precise date on hover
    const tooltip = document.createElement('div');
    tooltip.className = 'tooltip';
    tooltip.id = 'mosaic-timeline-tooltip';
    tooltip.style.top = '0px';
    tl.appendChild(tooltip);
  } catch {}
}

function updateTimelineCursor(ts) {
  try {
    const tl = document.getElementById('mosaic-timeline');
    if (!tl) return;
    const cursor = tl.querySelector('.cursor');
    if (!cursor) return;
    const ratio = timestampToScrollPosition(ts, mosaicOldestTimestamp, mosaicNewestTimestamp);
    cursor.style.top = `${Math.max(0, Math.min(100, ratio * 100))}%`;
  } catch {}
}

async function refreshMosaicAtTimestamp(targetTimestamp) {
  if (!targetTimestamp) return;
  const indicator = document.getElementById('mosaic-scroll-indicator');
  try { if (indicator) indicator.classList.add('show'); } catch {}
  const layout = computeMosaicLayout();
  const res = await loadMediaAroundTimestamp(targetTimestamp, layout.totalNeeded);
  const ref = res.referenceMedia || mosaicCurrentMedia;
  if (ref && ref.accessKey) mosaicCurrentMedia = ref;
  mosaicCurrentTimestamp = targetTimestamp;
  persistMosaicTimestamp(mosaicCurrentTimestamp);
  renderMosaicTiles(targetTimestamp, layout.columns, layout.viewportRows, layout.totalNeeded, mosaicCurrentMedia);
  updateTimelineCursor(targetTimestamp);
  if (indicator) {
    const date = new Date(targetTimestamp);
    indicator.textContent = date.toLocaleDateString('en-US', { year: 'numeric', month: 'short', day: 'numeric' });
    setTimeout(() => { try { indicator.classList.remove('show'); } catch {} }, 800);
  }
}

async function navigateMosaic(direction, steps = 1) {
  try {
    if (!mosaicCurrentMedia) {
      await refreshMosaicAtTimestamp(mosaicNewestTimestamp);
      return;
    }
    const dir = direction === 'next' ? 'next' : 'previous';
    let media = mosaicCurrentMedia;
    let moved = 0;

    // First, traverse through already cached neighbors without hitting the API
    while (moved < steps) {
      const neighbor = getAdjacentFromCache(media, dir);
      if (!neighbor) break;
      media = neighbor;
      if (isMediaDated(neighbor)) moved++; // count only valid-dated items
    }

    // Fetch remaining steps, de-duped and with adjacency linking
    while (moved < steps) {
      const neighbor = await fetchAdjacent(media, dir);
      if (!neighbor) break;
      media = neighbor;
      if (isMediaDated(neighbor)) moved++;
    }

    mosaicCurrentMedia = media;
    const ts = mediaTimestamp(media) || mosaicCurrentTimestamp || mosaicNewestTimestamp;
    mosaicCurrentTimestamp = ts;
    persistMosaicTimestamp(mosaicCurrentTimestamp);
    const layout = computeMosaicLayout();
    await ensureCacheWindowAround(mosaicCurrentMedia, layout.totalNeeded, 0.5);
    renderMosaicTiles(ts, layout.columns, layout.viewportRows, layout.totalNeeded, mosaicCurrentMedia);
    updateTimelineCursor(ts);
    const indicator = document.getElementById('mosaic-scroll-indicator');
    if (indicator) {
      const date = new Date(ts);
      indicator.textContent = date.toLocaleDateString('en-US', { year: 'numeric', month: 'short', day: 'numeric' });
      indicator.classList.add('show');
      setTimeout(() => { try { indicator.classList.remove('show'); } catch {} }, 800);
    }
  } catch (e) {
    console.warn('navigateMosaic failed', e);
  }
}

// Main mosaic loader with fixed timestamp-based scroller
async function loadMosaic() {
  const container = document.getElementById('mosaic-container');
  const indicator = document.getElementById('mosaic-scroll-indicator');
  const tabSection = document.getElementById('tab-mosaic');
  if (!container || !tabSection) return;

  // Make mosaic section focusable to ensure keyboard works without an extra click
  try {
    if (!tabSection.hasAttribute('tabindex')) tabSection.setAttribute('tabindex', '0');
    tabSection.setAttribute('aria-label', 'Mosaic view');
    if (!container.hasAttribute('tabindex')) container.setAttribute('tabindex', '-1');
    const timelineElInit = document.getElementById('mosaic-timeline');
    if (timelineElInit && !timelineElInit.hasAttribute('tabindex')) timelineElInit.setAttribute('tabindex', '-1');
  } catch {}

  function focusMosaic() {
    try {
      if (document.activeElement !== tabSection) {
        if (typeof tabSection.focus === 'function') tabSection.focus({ preventScroll: true });
      }
    } catch {}
  }
  // Proactively focus the mosaic when moving the mouse over it
  try {
    if (!tabSection.__focusWired) {
      tabSection.addEventListener('mouseenter', () => focusMosaic(), { passive: true });
      container.addEventListener('mouseenter', () => focusMosaic(), { passive: true });
      container.addEventListener('mousemove', () => focusMosaic(), { passive: true });
      container.addEventListener('wheel', () => focusMosaic(), { passive: true });
      tabSection.__focusWired = true;
    }
  } catch {}
  
  // Initialize timestamp range if not already done
  if (!mosaicOldestTimestamp || !mosaicNewestTimestamp) {
    if (mosaicIsLoading) return;
    mosaicIsLoading = true;
    
    try {
      // Fetch first (oldest) and last (newest) media
      const [firstMedia, lastMedia] = await Promise.all([
        api.getMedia('first'),
        api.getMedia('last')
      ]);
      
      if (!firstMedia || !lastMedia) {
        container.innerHTML = '<div style="padding:2rem;text-align:center;color:#6b7280;">No media found</div>';
        mosaicIsLoading = false;
        return;
      }
      
      // Ensure oldest/newest timestamps come from valid-dated media
      let oldestValid = firstMedia;
      let newestValid = lastMedia;
      let guard = 0;
      while (oldestValid && !isMediaDated(oldestValid) && guard < 1000) {
        guard++;
        try {
          const nxt = await api.getMedia('next', oldestValid.accessKey);
          if (!nxt || nxt.accessKey === oldestValid.accessKey) break;
          oldestValid = nxt;
        } catch { break; }
      }
      guard = 0;
      while (newestValid && !isMediaDated(newestValid) && guard < 1000) {
        guard++;
        try {
          const prv = await api.getMedia('previous', newestValid.accessKey);
          if (!prv || prv.accessKey === newestValid.accessKey) break;
          newestValid = prv;
        } catch { break; }
      }
      if (!oldestValid || !isMediaDated(oldestValid) || !newestValid || !isMediaDated(newestValid)) {
        container.innerHTML = '<div style="padding:2rem;text-align:center;color:#6b7280;">No dated media found</div>';
        mosaicIsLoading = false;
        return;
      }
      mosaicOldestTimestamp = mediaTimestamp(oldestValid);
      mosaicNewestTimestamp = mediaTimestamp(newestValid);
      
      // Clear container and set up virtual scroll
      container.innerHTML = '';
      mosaicMediaCache.clear();
      try { mosaicTileCache.clear(); mosaicNextByKey.clear(); mosaicPrevByKey.clear(); mosaicFetchCache.clear(); } catch {}
      
      // Create virtual scroll spacer
      const spacer = document.createElement('div');
      spacer.id = 'mosaic-scroll-spacer';
      spacer.style.height = `${MOSAIC_VIRTUAL_HEIGHT}px`;
      spacer.style.position = 'relative';
      container.appendChild(spacer);
      
      // Build clickable timeline and interactions
      buildMosaicTimeline();

      // Timeline click -> jump to timestamp
      const timelineEl = document.getElementById('mosaic-timeline');
      if (timelineEl && !timelineEl.__wiredClick) {
        timelineEl.addEventListener('click', async (e) => {
          const rect = timelineEl.getBoundingClientRect();
          const y = (e.clientY - rect.top) / Math.max(1, rect.height);
          const ratio = Math.max(0, Math.min(1, y));
          const ts = scrollPositionToTimestamp(ratio, mosaicOldestTimestamp, mosaicNewestTimestamp);
          await refreshMosaicAtTimestamp(ts);
        });
        // Keep keyboard focus on the mosaic when interacting with the timeline
        timelineEl.addEventListener('mouseenter', () => focusMosaic(), { passive: true });
        timelineEl.addEventListener('mousemove', () => focusMosaic(), { passive: true });
        timelineEl.addEventListener('wheel', () => focusMosaic(), { passive: true });
        timelineEl.__wiredClick = true;
      }

      // Timeline hover → show precise date tooltip
      if (timelineEl && !timelineEl.__wiredHover) {
        const updateTooltip = (e) => {
          const rect = timelineEl.getBoundingClientRect();
          const yPx = (e.clientY - rect.top);
          const ratio = Math.max(0, Math.min(1, yPx / Math.max(1, rect.height)));
          const ts = scrollPositionToTimestamp(ratio, mosaicOldestTimestamp, mosaicNewestTimestamp);
          const tip = timelineEl.querySelector('#mosaic-timeline-tooltip');
          if (tip && ts) {
            tip.style.top = `${Math.max(0, Math.min(rect.height, yPx))}px`;
            const d = new Date(ts);
            tip.textContent = d.toLocaleString(undefined, { year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit' });
            tip.classList.add('show');
          }
        };
        timelineEl.addEventListener('mousemove', updateTooltip);
        timelineEl.addEventListener('mouseenter', updateTooltip);
        timelineEl.addEventListener('mouseleave', () => {
          const tip = timelineEl.querySelector('#mosaic-timeline-tooltip');
          if (tip) tip.classList.remove('show');
        });
        timelineEl.__wiredHover = true;
      }

      // Wheel navigation (prevent page scroll)
      if (tabSection.__mosaicWheelListener) {
        tabSection.removeEventListener('wheel', tabSection.__mosaicWheelListener);
      }
      const handleWheel = (e) => {
        e.preventDefault();
        // Coalesce wheel deltas for smoother, faster navigation
        try {
          if (!tabSection.__wheelAccum) tabSection.__wheelAccum = 0;
          tabSection.__wheelAccum += (e && e.deltaY) || 0;
          if (tabSection.__wheelTimer) clearTimeout(tabSection.__wheelTimer);
          tabSection.__wheelTimer = setTimeout(() => {
            const accum = tabSection.__wheelAccum || 0;
            tabSection.__wheelAccum = 0;
            const dir = accum > 0 ? 'previous' : 'next'; // deltaY > 0 → scroll down → older (previous)
            const cols = computeMosaicLayout().columns || 1;
            const magnitude = Math.abs(accum);
            // Base step count scales with magnitude; each notch ~100 on many mice
            const base = Math.max(1, Math.round(magnitude / 80));
            // Convert to rows worth of items
            const steps = Math.max(1, Math.min(cols * 6, base * cols));
            navigateMosaic(dir, steps);
          }, 30);
        } catch {
          const dir = ((e && e.deltaY) || 0) > 0 ? 'previous' : 'next';
          navigateMosaic(dir, Math.max(1, computeMosaicLayout().columns));
        }
      };
      tabSection.__mosaicWheelListener = handleWheel;
      tabSection.addEventListener('wheel', handleWheel, { passive: false });

      // Keyboard navigation when Mosaic tab is active
      if (tabSection.__mosaicKeyListener) {
        document.removeEventListener('keydown', tabSection.__mosaicKeyListener);
      }
      const handleKey = (e) => {
        const active = document.getElementById('tab-mosaic')?.classList.contains('active');
        if (!active) return;
        if (e.key === 'PageDown') {
          e.preventDefault();
          const layout = computeMosaicLayout();
          const steps = Math.max(1, layout.columns * layout.viewportRows);
          navigateMosaic('previous', steps);
        } else if (e.key === 'PageUp') {
          e.preventDefault();
          const layout = computeMosaicLayout();
          const steps = Math.max(1, layout.columns * layout.viewportRows);
          navigateMosaic('next', steps);
        } else if (e.key === 'ArrowDown') {
          e.preventDefault();
          const steps = Math.max(1, computeMosaicLayout().columns);
          navigateMosaic('previous', steps);
        } else if (e.key === 'ArrowUp') {
          e.preventDefault();
          const steps = Math.max(1, computeMosaicLayout().columns);
          navigateMosaic('next', steps);
        } else if (e.key === 'Home') {
          e.preventDefault();
          if (mosaicNewestTimestamp) { refreshMosaicAtTimestamp(mosaicNewestTimestamp); }
        } else if (e.key === 'End') {
          e.preventDefault();
          if (mosaicOldestTimestamp) { refreshMosaicAtTimestamp(mosaicOldestTimestamp); }
        }
      };
      tabSection.__mosaicKeyListener = handleKey;
      document.addEventListener('keydown', handleKey, { passive: false });
      // Ensure mosaic keeps keyboard focus after wiring keys
      try { if (tabSection && typeof tabSection.focus === 'function') tabSection.focus({ preventScroll: true }); } catch {}

      // Rebuild timeline on resize
      if (tabSection.__mosaicResizeListener) {
        window.removeEventListener('resize', tabSection.__mosaicResizeListener);
      }
      const handleResize = () => {
        buildMosaicTimeline();
        if (mosaicCurrentTimestamp) {
          renderMosaicTiles(mosaicCurrentTimestamp);
          updateTimelineCursor(mosaicCurrentTimestamp);
        }
      };
      tabSection.__mosaicResizeListener = handleResize;
      window.addEventListener('resize', handleResize);
      
      // Load initial batch of media at the top (newest first)
      // Attempt to restore previously selected timestamp if available
      try {
        const savedTsRaw = getPersistedMosaicTimestamp();
        if (savedTsRaw && isValidTimestampStr(savedTsRaw)) {
          let tsToUse = savedTsRaw;
          try {
            const ms = Date.parse(savedTsRaw);
            const min = Date.parse(mosaicOldestTimestamp);
            const max = Date.parse(mosaicNewestTimestamp);
            if (Number.isFinite(ms) && Number.isFinite(min) && Number.isFinite(max)) {
              if (ms < min) tsToUse = mosaicOldestTimestamp;
              else if (ms > max) tsToUse = mosaicNewestTimestamp;
            }
          } catch {}
          await refreshMosaicAtTimestamp(tsToUse);
          return; // skip default initial batch; finally { mosaicIsLoading = false } will still run
        }
      } catch {}
      try {
        // Start from the newest valid media and load backwards, collecting only valid-dated items
        let current = newestValid;
        const initialBatch = [];
        let collected = 0;
        let guardInit = 0;
        while (collected < MOSAIC_LOAD_BUFFER && current && guardInit < MOSAIC_LOAD_BUFFER * 10) {
          guardInit++;
          if (isMediaDated(current)) {
            initialBatch.push(current);
            collected++;
          }
          try {
            const prev = await api.getMedia('previous', current.accessKey);
            if (!prev || prev.accessKey === current.accessKey) break;
            current = prev;
          } catch { break; }
        }
        
        // Add to cache
        for (const media of initialBatch) {
          if (media && media.accessKey) {
            mosaicMediaCache.set(media.accessKey, media);
          }
        }
        
        // Render initial tiles (top/newest)
        const layout = computeMosaicLayout();
        mosaicCurrentMedia = initialBatch[0] || newestValid;
        mosaicCurrentTimestamp = mediaTimestamp(mosaicCurrentMedia) || mosaicNewestTimestamp;
        renderMosaicTiles(mosaicCurrentTimestamp, layout.columns, layout.viewportRows, layout.totalNeeded, mosaicCurrentMedia);
        updateTimelineCursor(mosaicCurrentTimestamp);
        
      } catch (e) {
        console.warn('Error loading initial mosaic media:', e);
      }
      
    } catch (e) {
      console.error('Error initializing mosaic:', e);
      container.innerHTML = '<div style="padding:2rem;text-align:center;color:#6b7280;">Error loading photos</div>';
    } finally {
      mosaicIsLoading = false;
    }
  }
}

// Owners
function refreshOwnerTile(updated) {
  const list = document.getElementById('owners-list'); if (!list) return;
  const li = list.querySelector(`li[data-owner-id="${updated.id}"]`);
  if (!li) return;
  const birthStr = updated.birthDate ? new Date(updated.birthDate).toLocaleDateString() : '';
  li.innerHTML = `<h4>${updated.firstName} ${updated.lastName}</h4><div style="font-size:12px;color:#555">id: ${updated.id}${birthStr ? ' • birth: '+birthStr : ''}</div>
    <button class="ev-edit-btn" title="Edit">✎ Edit</button>`;
  const editBtn = li.querySelector('.ev-edit-btn');
  if (editBtn) { editBtn.onclick = (e) => { e.stopPropagation(); openOwnerEditModal(updated); }; }
}

function openOwnerEditModal(owner) {
  if (!owner) { showWarning('No owner'); return; }
  if (document.querySelector('.modal-overlay')) return;
  const overlay = document.createElement('div'); overlay.className = 'modal-overlay';
  const firstVal = owner.firstName || '';
  const lastVal = owner.lastName || '';
  const birthVal = owner.birthDate ? new Date(owner.birthDate) : null;
  const toDateInput = (d) => {
    try {
      if (!d) return '';
      const yyyy = d.getFullYear();
      const mm = String(d.getMonth()+1).padStart(2,'0');
      const dd = String(d.getDate()).padStart(2,'0');
      return `${yyyy}-${mm}-${dd}`;
    } catch { return ''; }
  };
  overlay.innerHTML = `
    <div class="modal" role="dialog" aria-modal="true" tabindex="-1">
      <header>
        <div>Edit owner</div>
        <button class="close" title="Close" style="background:none;border:none;font-size:18px;cursor:pointer">✕</button>
      </header>
      <div class="content">
        <div class="row">
          <div>
            <label>First name</label>
            <input type="text" id="ow-first" value="${(firstVal||'').replace(/&/g,'&amp;').replace(/"/g,'&quot;')}">
            <label style="margin-top:8px">Last name</label>
            <input type="text" id="ow-last" value="${(lastVal||'').replace(/&/g,'&amp;').replace(/"/g,'&quot;')}">
            <label style="margin-top:8px">Birthdate</label>
            <input type="date" id="ow-birth" value="${toDateInput(birthVal)}">
          </div>
        </div>
      </div>
      <footer>
        <button type="button" class="cancel">Cancel</button>
        <button type="button" class="save" style="background:#2563eb;color:#fff;border:1px solid #1d4ed8;border-radius:6px;padding:6px 10px;">Save</button>
      </footer>
    </div>`;
  document.body.appendChild(overlay);
  const modal = overlay.querySelector('.modal');
  const close = () => { overlay.remove(); };
  overlay.addEventListener('click', (e) => { if (e.target === overlay) close(); });
  overlay.querySelector('button.close')?.addEventListener('click', close);
  overlay.querySelector('button.cancel')?.addEventListener('click', (e)=>{ e.preventDefault(); e.stopPropagation(); close(); });
  setTimeout(()=>{ (modal.querySelector('#ow-first')||modal).focus(); }, 0);

  overlay.querySelector('button.save')?.addEventListener('click', async () => {
    const firstName = modal.querySelector('#ow-first').value.trim();
    const lastName = modal.querySelector('#ow-last').value.trim();
    const birth = modal.querySelector('#ow-birth').value; // yyyy-mm-dd or ''
    const body = { firstName, lastName };
    if (!birth) body.birthDate = null; else body.birthDate = `${birth}T00:00:00Z`;
    try {
      await api.updateOwner(owner.id, body);
      close();
      // refetch to get canonical data
      const owners = await api.listOwners();
      const updated = owners.find(x => x.id === owner.id) || { ...owner, firstName, lastName, birthDate: birth || null };
      refreshOwnerTile(updated);
      // Invalidate owners map cache used by Stores tab and refresh the owner select options
      ownersMapCache = null;
      const ownerSelect = document.getElementById('store-owner');
      if (ownerSelect) ownerSelect.innerHTML = owners.map(o => `<option value="${o.id}">${o.firstName} ${o.lastName}</option>`).join('');
    } catch (e) {
      showError('Failed to update owner');
    }
  });
}

function openOwnerCreateModal() {
  if (document.querySelector('.modal-overlay')) return;
  const overlay = document.createElement('div'); overlay.className = 'modal-overlay';
  overlay.innerHTML = `
    <div class="modal" role="dialog" aria-modal="true" tabindex="-1">
      <header>
        <div>Create owner</div>
        <button class="close" title="Close" style="background:none;border:none;font-size:18px;cursor:pointer">✕</button>
      </header>
      <div class="content">
        <div class="row">
          <div>
            <label>First name</label>
            <input type="text" id="owc-first" value="">
            <label style="margin-top:8px">Last name</label>
            <input type="text" id="owc-last" value="">
            <label style="margin-top:8px">Birthdate</label>
            <input type="date" id="owc-birth" value="">
          </div>
        </div>
      </div>
      <footer>
        <button type="button" class="cancel">Cancel</button>
        <button type="button" class="save" style="background:#2563eb;color:#fff;border:1px solid #1d4ed8;border-radius:6px;padding:6px 10px;">Create</button>
      </footer>
    </div>`;
  document.body.appendChild(overlay);
  const modal = overlay.querySelector('.modal');
  const close = () => { overlay.remove(); };
  overlay.addEventListener('click', (e) => { if (e.target === overlay) close(); });
  overlay.querySelector('button.close')?.addEventListener('click', close);
  overlay.querySelector('button.cancel')?.addEventListener('click', (e)=>{ e.preventDefault(); e.stopPropagation(); close(); });
  setTimeout(()=>{ (modal.querySelector('#owc-first')||modal).focus(); }, 0);

  overlay.querySelector('button.save')?.addEventListener('click', async () => {
    const firstName = modal.querySelector('#owc-first').value.trim();
    const lastName = modal.querySelector('#owc-last').value.trim();
    const birth = modal.querySelector('#owc-birth').value; // yyyy-mm-dd or ''
    if (!firstName || !lastName) { showWarning('First name and Last name are required'); return; }
    const body = { firstName, lastName };
    if (!birth) body.birthDate = null; else body.birthDate = `${birth}T00:00:00Z`;
    try {
      await api.createOwner(body);
      close();
      // Refresh owners list and stores owner select
      await loadOwners();
      ownersMapCache = null;
      try {
        const owners = await api.listOwners();
        const ownerSelect = document.getElementById('store-owner');
        if (ownerSelect) ownerSelect.innerHTML = owners.map(o => `<option value="${o.id}">${o.firstName} ${o.lastName}</option>`).join('');
      } catch {}
    } catch (e) {
      showError('Failed to create owner');
    }
  });
}

function initOwnersTab() {
  const refreshBtn = document.getElementById('refresh-owners');
  if (refreshBtn && !refreshBtn.__wired) { refreshBtn.addEventListener('click', loadOwners); refreshBtn.__wired = true; }
  const createBtn = document.getElementById('create-owner');
  if (createBtn && !createBtn.__wired) { createBtn.addEventListener('click', () => openOwnerCreateModal()); createBtn.__wired = true; }
}

async function loadOwners() {
  const list = $('#owners-list'); list.innerHTML = '';
  try {
    const owners = await api.listOwners();
    const ownerSelect = document.getElementById('store-owner');
    if (ownerSelect) ownerSelect.innerHTML = owners.map(o => `<option value="${o.id}">${o.firstName} ${o.lastName}</option>`).join('');

    // Lazy load thumbnails per owner when needed (similar to Events)
    const stateCache = new Map(); // originalId -> Promise<State>
    const limit = 4; // small concurrency for state->image resolution
    let inFlight = 0;
    const pending = [];
    const scheduled = new WeakSet();

    async function resolveAndRenderOwnerThumb(li, owner) {
      inFlight++;
      try {
        if (!owner.originalId) return;
        let p = stateCache.get(owner.originalId);
        if (!p) {
          p = api.getState(owner.originalId).catch(err => { stateCache.delete(owner.originalId); throw err; });
          stateCache.set(owner.originalId, p);
        }
        const st = await p;
        if (!st || !st.mediaAccessKey) return;
        const img = new Image();
        img.src = api.mediaMiniatureUrl(st.mediaAccessKey);
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
      li.__owner = o; // attach ref for observer
      li.dataset.ownerId = o.id || '';
      const birthStr = o.birthDate ? new Date(o.birthDate).toLocaleDateString() : '';
      li.innerHTML = `
        <div style="display: flex; align-items: center; gap: 12px;">
          <div class="owner-thumb" style="width:60px;height:60px;border-radius:6px;background:#f3f4f6;display:flex;align-items:center;justify-content:center;overflow:hidden;color:#9ca3af;font-size:10px;flex-shrink:0;">No image</div>
          <div style="flex: 1;">
            <h4 style="margin: 0 0 4px 0;">${o.firstName} ${o.lastName}</h4>
            <div style="font-size:12px;color:#555">id: ${o.id}${birthStr ? ' • birth: '+birthStr : ''}</div>
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
  } catch (e) { list.innerHTML = '<li>Failed to load owners</li>'; }
}

// Stores
let ownersMapCache = null; async function getOwnersMap() { if (ownersMapCache) return ownersMapCache; try { const owners = await api.listOwners(); const map = new Map(); for (const o of owners) map.set(o.id, `${o.firstName} ${o.lastName}`); ownersMapCache = map; return map; } catch { ownersMapCache = new Map(); return ownersMapCache; } }

async function loadStores() {
  const list = $('#stores-list'); list.innerHTML = '';
  try {
    const [stores, ownersMap] = await Promise.all([api.listStores(), getOwnersMap()]);
    
    // Lazy load thumbnails per store using owner originalId when needed
    const stateCache = new Map(); // originalId -> Promise<State>
    const ownerCache = new Map(); // ownerId -> Promise<Owner>
    const limit = 4; // small concurrency for state->image resolution
    let inFlight = 0;
    const pending = [];
    const scheduled = new WeakSet();

    async function resolveAndRenderStoreThumb(li, store) {
      inFlight++;
      try {
        if (!store.ownerId) return;
        let ownerPromise = ownerCache.get(store.ownerId);
        if (!ownerPromise) {
          ownerPromise = api.listOwners().then(owners => owners.find(o => o.id === store.ownerId)).catch(err => { ownerCache.delete(store.ownerId); throw err; });
          ownerCache.set(store.ownerId, ownerPromise);
        }
        const owner = await ownerPromise;
        if (!owner || !owner.originalId) return;
        
        let statePromise = stateCache.get(owner.originalId);
        if (!statePromise) {
          statePromise = api.getState(owner.originalId).catch(err => { stateCache.delete(owner.originalId); throw err; });
          stateCache.set(owner.originalId, statePromise);
        }
        const st = await statePromise;
        if (!st || !st.mediaAccessKey) return;
        
        const img = new Image();
        img.src = api.mediaMiniatureUrl(st.mediaAccessKey);
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
      li.__store = s; // attach ref for observer
      li.dataset.storeId = s.id || '';
      const title = `${s.name ? (s.name + ': ') : ''}${s.baseDirectory || ''}`;
      const ownerName = ownersMap.get(s.ownerId) || s.ownerName || s.ownerId || '';
      li.innerHTML = `
        <div style="display: flex; align-items: center; gap: 12px;">
          <div class="store-thumb" style="width:60px;height:60px;border-radius:6px;background:#f3f4f6;display:flex;align-items:center;justify-content:center;overflow:hidden;color:#9ca3af;font-size:10px;flex-shrink:0;">No image</div>
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
  } catch (e) { list.innerHTML = '<li>Failed to load stores</li>'; }
}

function refreshStoreTile(updated) {
  const list = document.getElementById('stores-list'); if (!list) return;
  const li = list.querySelector(`li[data-store-id="${updated.id}"]`);
  if (!li) return;
  getOwnersMap().then(ownersMap => {
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
  if (document.querySelector('.modal-overlay')) return;
  const overlay = document.createElement('div'); overlay.className = 'modal-overlay';
  const nameVal = store.name || '';
  const includeVal = store.includeMask || '';
  const ignoreVal = store.ignoreMask || '';
  overlay.innerHTML = `
    <div class="modal" role="dialog" aria-modal="true" tabindex="-1">
      <header>
        <div>Edit store</div>
        <button class="close" title="Close" style="background:none;border:none;font-size:18px;cursor:pointer">✕</button>
      </header>
      <div class="content">
        <div class="row">
          <div>
            <label>Name</label>
            <input type="text" id="st-name" value="${(nameVal||'').replace(/&/g,'&amp;').replace(/"/g,'&quot;')}">
            <label style="margin-top:8px">Include mask</label>
            <input type="text" id="st-include" value="${(includeVal||'').replace(/&/g,'&amp;').replace(/"/g,'&quot;')}">
            <label style="margin-top:8px">Ignore mask</label>
            <input type="text" id="st-ignore" value="${(ignoreVal||'').replace(/&/g,'&amp;').replace(/"/g,'&quot;')}">
          </div>
        </div>
      </div>
      <footer>
        <button type="button" class="cancel">Cancel</button>
        <button type="button" class="save" style="background:#2563eb;color:#fff;border:1px solid #1d4ed8;border-radius:6px;padding:6px 10px;">Save</button>
      </footer>
    </div>`;
  document.body.appendChild(overlay);
  const modal = overlay.querySelector('.modal');
  const close = () => { overlay.remove(); };
  overlay.addEventListener('click', (e) => { if (e.target === overlay) close(); });
  overlay.querySelector('button.close')?.addEventListener('click', close);
  overlay.querySelector('button.cancel')?.addEventListener('click', (e)=>{ e.preventDefault(); e.stopPropagation(); close(); });
  setTimeout(()=>{ (modal.querySelector('#st-name')||modal).focus(); }, 0);

  overlay.querySelector('button.save')?.addEventListener('click', async () => {
    const name = modal.querySelector('#st-name').value;
    const includeMask = modal.querySelector('#st-include').value;
    const ignoreMask = modal.querySelector('#st-ignore').value;
    const body = { name, includeMask, ignoreMask };
    try {
      await api.updateStore(store.id, body);
      close();
      // refetch this store from list to get latest values
      const stores = await api.listStores();
      const updated = stores.find(x => x.id === store.id) || { ...store, name, includeMask, ignoreMask };
      refreshStoreTile(updated);
    } catch (e) {
      showError('Failed to update store');
    }
  });
}

async function openStoreCreateModal() {
  if (document.querySelector('.modal-overlay')) return;
  // Render modal immediately; owners will be fetched asynchronously to avoid UI freeze
  let ownerNames = [];
  const overlay = document.createElement('div'); overlay.className = 'modal-overlay';
  const datalistId = 'stc-owner-list';
  overlay.innerHTML = `
    <div class="modal" role="dialog" aria-modal="true" tabindex="-1">
      <header>
        <div>Create store</div>
        <button class="close" title="Close" style="background:none;border:none;font-size:18px;cursor:pointer">✕</button>
      </header>
      <div class="content">
        <div class="row">
          <div>
            <label>Name</label>
            <input type="text" id="stc-name" value="">
            <label style="margin-top:8px">Base directory</label>
            <input type="text" id="stc-basedir" value="" placeholder="/path/to/photos" required>
            <label style="margin-top:8px">Owner</label>
            <input type="text" id="stc-owner" list="${datalistId}" placeholder="Loading owners…" autocomplete="off" disabled>
            <datalist id="${datalistId}"></datalist>
            <label style="margin-top:8px">Include mask</label>
            <input type="text" id="stc-include" value="">
            <label style="margin-top:8px">Ignore mask</label>
            <input type="text" id="stc-ignore" value="">
          </div>
        </div>
      </div>
      <footer>
        <button type="button" class="cancel">Cancel</button>
        <button type="button" class="save" style="background:#2563eb;color:#fff;border:1px solid #1d4ed8;border-radius:6px;padding:6px 10px;">Create</button>
      </footer>
    </div>`;
  document.body.appendChild(overlay);
  const modal = overlay.querySelector('.modal');
  const close = () => { overlay.remove(); };
  overlay.addEventListener('click', (e) => { if (e.target === overlay) close(); });
  overlay.querySelector('button.close')?.addEventListener('click', close);
  overlay.querySelector('button.cancel')?.addEventListener('click', (e)=>{ e.preventDefault(); e.stopPropagation(); close(); });
  setTimeout(()=>{ (modal.querySelector('#stc-name')||modal).focus(); }, 0);

  // Fetch owners asynchronously and populate the datalist
  (async () => {
    try {
      const owners = await api.listOwners();
      ownerNames = owners.map(o => ({ id: o.id, name: `${o.firstName || ''} ${o.lastName || ''}`.trim() }));
      const optionsHtml = ownerNames.map(o => `<option value="${o.name.replace(/&/g,'&amp;').replace(/"/g,'&quot;')}"></option>`).join('');
      const dl = overlay.querySelector(`#${datalistId}`);
      if (dl) dl.innerHTML = optionsHtml;
      const ownerInput = overlay.querySelector('#stc-owner');
      if (ownerInput) {
        ownerInput.disabled = false;
        ownerInput.placeholder = 'Type owner name…';
      }
    } catch (e) {
      const ownerInput = overlay.querySelector('#stc-owner');
      if (ownerInput) {
        ownerInput.disabled = true;
        ownerInput.placeholder = 'Failed to load owners';
      }
    }
  })();

  function resolveOwnerId(name) {
    const n = (name||'').trim(); if (!n) return null;
    const match = ownerNames.find(o => o.name.toLowerCase() === n.toLowerCase());
    if (match) return match.id;
    // try startsWith unique
    const candidates = ownerNames.filter(o => o.name.toLowerCase().startsWith(n.toLowerCase()));
    if (candidates.length === 1) return candidates[0].id;
    return null;
  }

  overlay.querySelector('button.save')?.addEventListener('click', async () => {
    const name = modal.querySelector('#stc-name').value.trim();
    const baseDirectory = modal.querySelector('#stc-basedir').value.trim();
    const ownerName = modal.querySelector('#stc-owner').value.trim();
    const includeMask = modal.querySelector('#stc-include').value.trim();
    const ignoreMask = modal.querySelector('#stc-ignore').value.trim();
    if (!baseDirectory) { showWarning('Base directory is required'); return; }
    const ownerId = resolveOwnerId(ownerName);
    if (!ownerId) { showWarning('Please select a valid owner by name'); return; }
    const body = { name: name || null, ownerId, baseDirectory, includeMask: includeMask || null, ignoreMask: ignoreMask || null };
    try {
      await api.createStore(body);
      close();
      await loadStores();
    } catch(e) {
      showError('Failed to create store');
    }
  });
}

function initStoresTab() {
  const refreshBtn = document.getElementById('refresh-stores'); if (refreshBtn && !refreshBtn.__wired) { refreshBtn.addEventListener('click', loadStores); refreshBtn.__wired = true; }
  const createBtn = document.getElementById('create-store'); if (createBtn && !createBtn.__wired) { createBtn.addEventListener('click', () => openStoreCreateModal()); createBtn.__wired = true; }
}

// Settings
let syncPollTimer = null;
function formatDuration(startedAt) {
  const now = new Date();
  const start = new Date(startedAt);
  const diffMs = now - start;
  if (diffMs < 0) return '0s';
  
  const totalSeconds = Math.floor(diffMs / 1000);
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  
  let result = '';
  if (hours > 0) result += `${hours}h`;
  if (minutes > 0) result += `${minutes}m`;
  if (seconds > 0 || result === '') result += `${seconds}s`;
  
  return result;
}

async function refreshSyncStatus() {
  const statusEl = document.getElementById('sync-status');
  const btn = document.getElementById('btn-sync');
  try {
    const st = await api.synchronizeStatus();
    const running = !!st?.running;
    const count = typeof st?.processedCount === 'number' ? st.processedCount : 0;
    const updated = st?.lastUpdated ? new Date(st.lastUpdated).toLocaleString() : 'never';
    
    if (running && st?.startedAt) {
      const duration = formatDuration(st.startedAt);
      statusEl.textContent = `Running for ${duration}… processed ${count} item(s). Last update: ${updated}`;
    } else if (running) {
      statusEl.textContent = `Running… processed ${count} item(s). Last update: ${updated}`;
    } else {
      statusEl.textContent = `Idle. Last run update: ${updated}. Total processed: ${count}`;
    }
    
    if (btn) { btn.disabled = running; btn.title = running ? 'Synchronization is running' : 'Synchronize all stores'; }
  } catch (e) {
    statusEl.textContent = 'Unable to get synchronization status.';
    if (btn) { btn.disabled = false; btn.title = 'Synchronize all stores'; }
  }
}
function startSyncPolling() {
  if (syncPollTimer) return;
  syncPollTimer = setInterval(refreshSyncStatus, 3000);
}
function stopSyncPolling() {
  if (syncPollTimer) { clearInterval(syncPollTimer); syncPollTimer = null; }
}
function initSettings() {
  const btn = document.getElementById('btn-sync');
  if (btn && !btn.__wired) {
    btn.addEventListener('click', async () => {
      const statusEl = document.getElementById('sync-status');
      statusEl.textContent = 'Starting synchronization…';
      btn.disabled = true;
      try { await api.synchronizeStart(); await refreshSyncStatus(); }
      catch { statusEl.textContent = 'Failed to start synchronization.'; btn.disabled = false; }
    });
    btn.__wired = true;
  }
  // Don't automatically start polling - it will be started when Settings tab is activated
}

function init() {
  initTabs();
  initViewerControls();
  // Ensure fullscreen overlay exists inside image container
  const cont = document.querySelector('.image-container');
  if (cont && !document.getElementById('fs-overlay')) {
    const ov = document.createElement('div');
    ov.id = 'fs-overlay';
    ov.className = 'fs-overlay';
    ov.innerHTML = '';
    cont.appendChild(ov);
  }
  // Inject hover Edit button on image (bottom-right)
  if (cont && !document.getElementById('img-edit-btn')) {
    const btn = document.createElement('button');
    btn.id = 'img-edit-btn';
    btn.className = 'img-edit-btn';
    btn.type = 'button';
    btn.title = 'Edit media';
    btn.textContent = '✎ Edit';
    btn.addEventListener('click', (e) => { e.stopPropagation(); if (currentMedia) openMediaEditModal(currentMedia); else alert('No media loaded'); });
    cont.appendChild(btn);
  }
  initEventsTab();
  initOwnersTab();
  initStoresTab();
  initSettings();
  // Initial media: restore last viewed image if possible, else random
  try {
    const lastKey = localStorage.getItem('viewer.lastMediaAccessKey');
    if (lastKey) {
      api.getMediaByKey(lastKey).then(m => showMedia(m)).catch(() => loadMedia('random'));
    } else {
      loadMedia('random');
    }
  } catch {
    loadMedia('random');
  }
}

document.addEventListener('DOMContentLoaded', init);
