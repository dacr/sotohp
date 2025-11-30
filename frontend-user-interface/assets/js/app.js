// Compiled JavaScript (manually authored) corresponding to TypeScript sources.
// Uses axios (via CDN) and Leaflet (via CDN). ES module for clarity.

class ApiClient {
  // Faces & persons overlay support added
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
  mediaOriginalUrl(mediaAccessKey) { return `/api/media/${encodeURIComponent(mediaAccessKey)}/content/original`; }
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
  // Persons
  async listPersons() { return await this.#fetchNdjson('/api/persons'); }
  async getPerson(personId) { const res = await this.http.get(`/api/person/${encodeURIComponent(personId)}`); return res.data; }
  async createPerson(body) { const res = await this.http.post('/api/person', body); return res.data; }
  async updatePerson(personId, body) { await this.http.put(`/api/person/${encodeURIComponent(personId)}`, body); }
  async deletePerson(personId) { await this.http.delete(`/api/person/${encodeURIComponent(personId)}`); }
  async updatePersonFace(personId, faceId) { await this.http.put(`/api/person/${encodeURIComponent(personId)}/face/${encodeURIComponent(faceId)}`); }
  async listPersonFaces(personId) { return await this.#fetchNdjson(`/api/person/${encodeURIComponent(personId)}/faces`); }
  faceImageUrl(faceId) { return `/api/face/${encodeURIComponent(faceId)}/content`; }
  // Faces endpoints
  async getMediaFaces(mediaAccessKey) { const res = await this.http.get(`/api/media/${encodeURIComponent(mediaAccessKey)}/faces`); return res.data; }
  async getFace(faceId) { const res = await this.http.get(`/api/face/${encodeURIComponent(faceId)}`); return res.data; }
  async setFacePerson(faceId, personId) {
    // Some backends are strict about PUT without an explicit body; pass null to avoid
    // accidental serialization of "undefined" and ensure a zero-length entity.
    await this.http.put(
      `/api/face/${encodeURIComponent(faceId)}/person/${encodeURIComponent(personId)}`,
      null
    );
  }
  async removeFacePerson(faceId) { await this.http.delete(`/api/face/${encodeURIComponent(faceId)}/person`); }
  async deleteFace(faceId) { await this.http.delete(`/api/face/${encodeURIComponent(faceId)}`); }
  async createFace(body) { const res = await this.http.post('/api/face', body); return res.data; }
  async listStores() { return await this.#fetchNdjson('/api/stores'); }
  async getStore(storeId) { const res = await this.http.get(`/api/store/${encodeURIComponent(storeId)}`); return res.data; }
  async updateStore(storeId, body) { await this.http.put(`/api/store/${encodeURIComponent(storeId)}`, body); }
  async createStore(body) { const res = await this.http.post('/api/store', body); return res.data; }
  async synchronizeStatus() { const res = await this.http.get('/api/admin/synchronize'); return res.data; }
  async synchronizeStart(addedThoseLastDays) { const params = {}; if (typeof addedThoseLastDays === 'number' && Number.isFinite(addedThoseLastDays)) params.addedThoseLastDays = addedThoseLastDays; await this.http.put('/api/admin/synchronize', null, { params }); }
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
let facesEnabled = false;
// Add Face draw mode state
let addFaceMode = false;
let addFaceStart = null; // {x,y} in container coords
let addFaceRectEl = null;
let addFacePosting = false; // single-shot guard to avoid duplicate creation on mouseup
function isAddFaceModeActive() { return !!addFaceMode; }
let facesOverlay = null; // overlay container element
let currentFaces = []; // array of faces for current media
let personsCache = null; // Map personId -> person object
let mediaFacesSeq = 0; // sequence to cancel stale loads
let slideshowPlaying = false;

// Recent persons selection (LRU of up to 10 personIds) persisted in localStorage
function getRecentPersonsIds() {
  try {
    const raw = localStorage.getItem('viewer.recentPersons') || '[]';
    const arr = Array.isArray(JSON.parse(raw)) ? JSON.parse(raw) : [];
    // Filter invalid and deduplicate while preserving order
    const seen = new Set();
    const out = [];
    for (const id of arr) {
      if (typeof id !== 'string') continue;
      if (seen.has(id)) continue;
      seen.add(id);
      out.push(id);
      if (out.length >= 10) break;
    }
    return out;
  } catch { return []; }
}
function pushRecentPersonId(personId) {
  try {
    if (!personId || typeof personId !== 'string') return;
    const ids = getRecentPersonsIds();
    const without = ids.filter(x => x !== personId);
    const updated = [personId, ...without].slice(0, 10);
    localStorage.setItem('viewer.recentPersons', JSON.stringify(updated));
  } catch {}
}

// Global keyboard shortcuts for modals:
// - Escape: close the topmost modal overlay
// - Ctrl+Enter / Cmd+Enter: trigger the primary action (Save/Create) in the topmost modal
// Applies to any create/update form that uses the common .modal-overlay wrapper
document.addEventListener('keydown', (e) => {
  try {
    const overlays = document.querySelectorAll('.modal-overlay');
    if (!overlays || overlays.length === 0) return;
    const last = overlays[overlays.length - 1];

    // Escape closes the modal
    if (e.key === 'Escape') {
      e.preventDefault();
      e.stopPropagation();
      if (last && typeof last.remove === 'function') last.remove();
      return;
    }

    // Ctrl+Enter (or Cmd+Enter on macOS) activates the primary action
    const isEnter = e.key === 'Enter' || e.code === 'Enter' || e.keyCode === 13;
    const hasCtrlOrMeta = e.ctrlKey || e.metaKey;
    if (isEnter && hasCtrlOrMeta) {
      const modal = last.querySelector('.modal');
      if (!modal) return;
      // Prefer explicit primary button with class .save
      let primaryBtn = modal.querySelector('button.save');
      // Fallback: first button in footer that is not a .cancel
      if (!primaryBtn) {
        const footer = modal.querySelector('footer');
        if (footer) {
          const btns = Array.from(footer.querySelectorAll('button'));
          primaryBtn = btns.find(b => !b.classList.contains('cancel')) || null;
        }
      }
      if (primaryBtn && typeof primaryBtn.click === 'function') {
        e.preventDefault();
        e.stopPropagation();
        primaryBtn.click();
      }
    }
  } catch {}
});

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
  if (name === 'map') {
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
  if (name === 'viewer') {
    try {
      const sec = document.getElementById('tab-viewer');
      if (sec) setTimeout(() => { try { sec.focus({ preventScroll: true }); } catch { try { sec.focus(); } catch {} } }, 0);
    } catch {}
  }
  if (name === 'events') { 
    initEventsTab(); 
    ensureEventsLoaded(); 
    try {
      const sec = document.getElementById('tab-events');
      if (sec) setTimeout(() => { try { sec.focus({ preventScroll: true }); } catch { try { sec.focus(); } catch {} } }, 0);
    } catch {}
  }
  if (name === 'persons') loadPersons();
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
  const initialRaw = location.hash?.slice(1) || 'viewer';
  const initial = (initialRaw === 'world') ? 'map' : initialRaw;
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
  if (img) {
    // Determine which size to use: original only in fullscreen, otherwise normalized
    const cont = document.querySelector('.image-container');
    // Clear faces overlay immediately while new image loads to avoid misaligned boxes
    if (facesEnabled) { clearFacesOverlay(); }
    const isFs = !!(document.fullscreenElement && cont && document.fullscreenElement === cont);
    const srcUrl = (isFs ? api.mediaOriginalUrl(media.accessKey) : api.mediaNormalizedUrl(media.accessKey)) + `?t=${Date.now()}`;
    // Smooth fade transition for image swaps
    try { img.style.opacity = '0'; } catch {}
    // Start zoom exactly when the new image is displayed
    img.onload = () => {
      // When the image is displayed, re-render faces overlay to match actual size
      if (facesEnabled) { try { renderFaces(); } catch {} }
      try { img.style.opacity = '1'; } catch {}
      try {
        // Update zoom duration from slideshow controls (fallback 20s)
        const durActive = document.querySelector('#ss-duration button.active') || document.querySelector('#ss-duration button[data-secs="20"]');
        const secs = parseInt(durActive?.dataset?.secs || '20', 10) || 20;
        img.style.setProperty('--viewer-zoom-duration', `${secs}s`);
      } catch {}
      if (slideshowPlaying) {
        // Restart animation in sync with display time
        img.classList.remove('zooming');
        void img.offsetWidth; // force reflow to restart animation
        img.classList.add('zooming');
      } else {
        // Ensure no zoom effect while slideshow is paused/stopped
        img.classList.remove('zooming');
      }
      // Clear handler
      img.onload = null;
    };
    img.src = srcUrl; // cache bust
  }
  // Load faces for this media if overlay is enabled
  if (facesEnabled) { try { loadFacesForCurrentMedia(); } catch {} }
  const date = media.shootDateTime || media.original?.cameraShootDateTime || '-';
  const dateStr = date ? new Date(date).toLocaleString() : '-';
  const ev0 = (media.events && media.events.length > 0) ? media.events[0] : null;
  const eventName = ev0 ? (ev0.name || '(no name)') : '-';
  const dateEl = document.getElementById('info-date');
  if (dateEl) {
    dateEl.textContent = dateStr;
    // Make date clickable to open Mosaic at this timestamp if valid
    const ts = (media.shootDateTime || (media.original && media.original.cameraShootDateTime)) || null;
    const valid = ts && !Number.isNaN(new Date(ts).getTime());
    dateEl.style.cursor = valid ? 'pointer' : 'default';
    dateEl.title = valid ? 'Open in Mosaic at this date' : '';
    dateEl.onclick = null;
    if (valid) {
      dateEl.onclick = async () => { try { await goToMosaicAtTimestamp(ts); } catch {} };
    }
  }
  // Description just after date
  const descEl = document.getElementById('info-description');
  if (descEl) {
    const desc = (media && typeof media.description === 'string') ? media.description.trim() : '';
    descEl.textContent = desc || '-';
    descEl.title = desc || '';
  }
  const evEl = $('#info-event');
  if (evEl) {
    evEl.textContent = eventName;
    // Reset any previous interactivity
    evEl.style.cursor = 'default';
    evEl.title = '';
    evEl.onclick = null;
    if (ev0 && ev0.id) {
      evEl.style.cursor = 'pointer';
      evEl.title = 'Open in Events';
      evEl.onclick = async () => { try { await goToEventsById(ev0.id); } catch {} };
    }
  }
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

function ensureFacesOverlay() {
  try {
    const cont = document.querySelector('.image-container');
    if (!cont) return null;
    let ov = cont.querySelector('.faces-overlay');
    if (!ov) {
      ov = document.createElement('div');
      ov.className = 'faces-overlay';
      cont.appendChild(ov);
    }
    return ov;
  } catch { return null; }
}

function getRenderedImageRect() {
  const cont = document.querySelector('.image-container');
  const img = document.getElementById('main-image');
  if (!cont || !img || !img.naturalWidth || !img.naturalHeight) return { left: 0, top: 0, width: 0, height: 0 };
  const cw = cont.clientWidth || 0;
  const ch = cont.clientHeight || 0;
  if (cw <= 0 || ch <= 0) return { left: 0, top: 0, width: 0, height: 0 };
  const nw = img.naturalWidth;
  const nh = img.naturalHeight;
  const scale = Math.min(cw / nw, ch / nh);
  const w = Math.max(0, Math.round(nw * scale));
  const h = Math.max(0, Math.round(nh * scale));
  const left = Math.round((cw - w) / 2);
  const top = Math.round((ch - h) / 2);
  return { left, top, width: w, height: h };
}

function clearFacesOverlay() {
  const ov = ensureFacesOverlay();
  if (ov) ov.innerHTML = '';
  try { updateConfirmAllButtonVisibility(); } catch {}
}

// Confirm-all inferred faces button lifecycle ------------------------------------------------------
function hasInferredPending() {
  try {
    if (!Array.isArray(currentFaces) || currentFaces.length === 0) return false;
    return currentFaces.some(f => (!f.identifiedPersonId) && !!f.inferredIdentifiedPersonId);
  } catch { return false; }
}

function ensureConfirmAllButton() {
  try {
    const cont = document.querySelector('.image-container');
    if (!cont) return null;
    let btn = cont.querySelector('.img-confirm-btn');
    if (!btn) {
      btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'img-confirm-btn';
      btn.title = 'Confirm all inferred faces';
      btn.setAttribute('aria-label', 'Confirm all inferred faces');
      btn.textContent = 'Confirm all';
      btn.style.display = 'none';
      cont.appendChild(btn);
      // Wire click handler once
      btn.addEventListener('click', async (e) => {
        e.preventDefault(); e.stopPropagation();
        await confirmAllInferredFaces(btn);
      });
    }
    return btn;
  } catch { return null; }
}

function updateConfirmAllButtonVisibility() {
  const btn = ensureConfirmAllButton();
  try {
    if (!btn) return;
    const shouldShow = !!facesEnabled && hasInferredPending() && !isAddFaceModeActive();
    btn.style.display = shouldShow ? 'inline-flex' : 'none';
    btn.disabled = !shouldShow;
    btn.textContent = 'Confirm all';
    btn.title = 'Confirm all inferred faces';
  } catch {}
}

// Add Face draw mode ---------------------------------------------------------------------------------
function toggleAddFaceMode(on) {
  const cont = document.querySelector('.image-container');
  if (!cont) return;
  if (on && addFaceMode) return; // already on
  if (!on && !addFaceMode) return; // already off
  if (on) {
    if (!currentMedia) { showWarning('No media to add a face to'); return; }
    addFaceMode = true;
    addFacePosting = false;
    try { cont.style.cursor = 'crosshair'; } catch {}
    try { ensureFacesOverlay(); } catch {}
    // Key ESC to cancel
    const onKey = (e) => {
      if (e.key === 'Escape') { e.preventDefault(); cancelAddFaceMode(); }
    };
    document.addEventListener('keydown', onKey, { capture: true });
    cont.__addFaceOnKey = onKey;
    // Cancel on fullscreen change or tab switch resize
    const onFs = () => cancelAddFaceMode();
    document.addEventListener('fullscreenchange', onFs);
    cont.__addFaceOnFs = onFs;
    const onResize = () => cancelAddFaceMode();
    window.addEventListener('resize', onResize);
    cont.__addFaceOnResize = onResize;
    updateConfirmAllButtonVisibility();
    showInfo('Add face: drag on the photo, release to create. Press Esc to cancel.');
  } else {
    cancelAddFaceMode();
  }
}
function cancelAddFaceMode() {
  const cont = document.querySelector('.image-container');
  addFaceMode = false;
  addFaceStart = null;
  addFacePosting = false;
  try { if (addFaceRectEl && addFaceRectEl.parentNode) addFaceRectEl.parentNode.removeChild(addFaceRectEl); } catch {}
  addFaceRectEl = null;
  if (cont) {
    try { cont.style.cursor = ''; } catch {}
    if (cont.__addFaceOnKey) { document.removeEventListener('keydown', cont.__addFaceOnKey, { capture: true }); cont.__addFaceOnKey = null; }
    if (cont.__addFaceOnFs) { document.removeEventListener('fullscreenchange', cont.__addFaceOnFs); cont.__addFaceOnFs = null; }
    if (cont.__addFaceOnResize) { window.removeEventListener('resize', cont.__addFaceOnResize); cont.__addFaceOnResize = null; }
  }
  updateConfirmAllButtonVisibility();
}

function handleAddFacePointerDown(ev) {
  if (!isAddFaceModeActive()) return;
  const cont = document.querySelector('.image-container');
  const imgRect = getRenderedImageRect();
  const contRect = cont.getBoundingClientRect();
  const x = (ev.clientX ?? 0) - contRect.left;
  const y = (ev.clientY ?? 0) - contRect.top;
  // Must start inside the image rect
  if (x < imgRect.left || y < imgRect.top || x > imgRect.left + imgRect.width || y > imgRect.top + imgRect.height) {
    showWarning('Start dragging inside the photo area');
    return;
  }
  addFaceStart = { x, y };
  if (!addFaceRectEl) {
    addFaceRectEl = document.createElement('div');
    addFaceRectEl.className = 'draw-rect';
    addFaceRectEl.style.left = `${x}px`;
    addFaceRectEl.style.top = `${y}px`;
    addFaceRectEl.style.width = '0px';
    addFaceRectEl.style.height = '0px';
    cont.appendChild(addFaceRectEl);
  }
  ev.preventDefault(); ev.stopPropagation();
}
function handleAddFacePointerMove(ev) {
  if (!isAddFaceModeActive() || !addFaceStart || !addFaceRectEl) return;
  const cont = document.querySelector('.image-container');
  const contRect = cont.getBoundingClientRect();
  const imgRect = getRenderedImageRect();
  let x = (ev.clientX ?? 0) - contRect.left;
  let y = (ev.clientY ?? 0) - contRect.top;
  // Clamp inside image rect
  x = Math.max(imgRect.left, Math.min(imgRect.left + imgRect.width, x));
  y = Math.max(imgRect.top, Math.min(imgRect.top + imgRect.height, y));
  const sx = Math.max(imgRect.left, Math.min(imgRect.left + imgRect.width, addFaceStart.x));
  const sy = Math.max(imgRect.top, Math.min(imgRect.top + imgRect.height, addFaceStart.y));
  const left = Math.min(sx, x);
  const top = Math.min(sy, y);
  const width = Math.abs(x - sx);
  const height = Math.abs(y - sy);
  addFaceRectEl.style.left = `${left}px`;
  addFaceRectEl.style.top = `${top}px`;
  addFaceRectEl.style.width = `${width}px`;
  addFaceRectEl.style.height = `${height}px`;
  ev.preventDefault();
}
async function handleAddFacePointerUp(ev) {
  if (!isAddFaceModeActive() || !addFaceStart) return;
  if (addFacePosting) { ev.preventDefault?.(); ev.stopPropagation?.(); return; }
  addFacePosting = true;
  const cont = document.querySelector('.image-container');
  const contRect = cont.getBoundingClientRect();
  const imgRect = getRenderedImageRect();
  let x2 = (ev.clientX ?? 0) - contRect.left;
  let y2 = (ev.clientY ?? 0) - contRect.top;
  // Clamp end inside image
  x2 = Math.max(imgRect.left, Math.min(imgRect.left + imgRect.width, x2));
  y2 = Math.max(imgRect.top, Math.min(imgRect.top + imgRect.height, y2));
  const sx = Math.max(imgRect.left, Math.min(imgRect.left + imgRect.width, addFaceStart.x));
  const sy = Math.max(imgRect.top, Math.min(imgRect.top + imgRect.height, addFaceStart.y));
  const left = Math.min(sx, x2);
  const top = Math.min(sy, y2);
  const widthPx = Math.abs(x2 - sx);
  const heightPx = Math.abs(y2 - sy);
  // Minimum size check (at least 8px and 0.005 relative)
  const minPx = 8;
  const minRel = 0.005;
  const wRel = imgRect.width > 0 ? widthPx / imgRect.width : 0;
  const hRel = imgRect.height > 0 ? heightPx / imgRect.height : 0;
  if (widthPx < minPx || heightPx < minPx || wRel < minRel || hRel < minRel) {
    cancelAddFaceMode();
    showWarning('Box too small, canceled');
    addFacePosting = false;
    return;
  }
  // Compute normalized box relative to the image top-left
  const nx = imgRect.width > 0 ? (left - imgRect.left) / imgRect.width : 0;
  const ny = imgRect.height > 0 ? (top - imgRect.top) / imgRect.height : 0;
  const nw = imgRect.width > 0 ? widthPx / imgRect.width : 0;
  const nh = imgRect.height > 0 ? heightPx / imgRect.height : 0;
  const box = { x: clamp01(nx), y: clamp01(ny), width: clamp01(nw), height: clamp01(nh) };
  // Get originalId
  const originalId = currentMedia?.original?.id;
  if (!originalId) {
    cancelAddFaceMode();
    showError('Unable to resolve original photo id for this media');
    addFacePosting = false;
    return;
  }
  try {
    // Create the face
    const created = await api.createFace({ originalId, box });
    // Push into currentFaces and re-render (avoid duplicates)
    try {
      const newId = created.faceId || created.id;
      const exists = currentFaces.some(f => (f.faceId||f.id) === newId);
      if (!exists) currentFaces.push(created);
    } catch {}
    try { renderFaces(); } catch {}
    showSuccess('Face created');
  } catch (err) {
    console.error('Failed to create face', err);
    showError('Failed to create face');
  } finally {
    addFacePosting = false;
    cancelAddFaceMode();
  }
  ev.preventDefault?.(); ev.stopPropagation?.();
}
function clamp01(v) { return Math.max(0, Math.min(1, v)); }

async function confirmAllInferredFaces(btnRef) {
  const cont = document.querySelector('.image-container');
  const btn = btnRef || (cont && cont.querySelector('.img-confirm-btn'));
  const targets = (currentFaces || []).filter(f => (!f.identifiedPersonId) && f.inferredIdentifiedPersonId);
  if (!targets || targets.length === 0) { updateConfirmAllButtonVisibility(); return; }
  const namesCount = targets.length;
  try {
    if (btn) { btn.disabled = true; btn.textContent = 'Confirming…'; }
  } catch {}
  let ok = 0, ko = 0;
  // Simple limited concurrency runner
  const limit = 6;
  let index = 0;
  const runNext = async () => {
    if (index >= targets.length) return;
    const f = targets[index++];
    try {
      await api.setFacePerson(f.faceId || f.id, f.inferredIdentifiedPersonId);
      const faceId = f.faceId || f.id;
      const idx = currentFaces.findIndex(x => (x.faceId||x.id) === faceId);
      const pid = f.inferredIdentifiedPersonId;
      if (idx >= 0) { currentFaces[idx].identifiedPersonId = pid; currentFaces[idx].inferredIdentifiedPersonId = null; }
      else { f.identifiedPersonId = pid; f.inferredIdentifiedPersonId = null; }
      try { if (pid) pushRecentPersonId(pid); } catch {}
      ok++;
    } catch (e) {
      console.warn('Confirm-all failed for face', f, e);
      ko++;
    }
    await runNext();
  };
  const workers = [];
  for (let i=0;i<Math.min(limit, targets.length);i++) workers.push(runNext());
  await Promise.all(workers);
  try { await ensurePersonsCache(); } catch {}
  renderFaces();
  updateConfirmAllButtonVisibility();
  if (btn) { btn.disabled = false; btn.textContent = 'Confirm all'; }
  if (ko === 0) showSuccess(`Confirmed ${ok} face${ok>1?'s':''}`);
  else if (ok === 0) showError('Failed to confirm inferred faces');
  else showWarning(`Confirmed ${ok}, failed ${ko}`);
}

function personsName(personId) {
  try {
    const p = personsCache && personsCache.get(personId);
    if (!p) return { first: '', full: '' };
    const first = (p.firstName || '').trim();
    const last = (p.lastName || '').trim();
    return { first, full: `${first}${last ? ' ' + last : ''}` };
  } catch { return { first: '', full: '' }; }
}

async function ensurePersonsCache() {
  if (personsCache) return personsCache;
  try {
    const items = await api.listPersons();
    const map = new Map();
    for (const p of items) { if (p && p.id) map.set(p.id, p); }
    personsCache = map;
    return personsCache;
  } catch { personsCache = new Map(); return personsCache; }
}

function renderFaces() {
  if (!facesEnabled) { clearFacesOverlay(); return; }
  const ov = ensureFacesOverlay();
  if (!ov) return;
  ov.innerHTML = '';
  const rect = getRenderedImageRect();
  if (rect.width <= 0 || rect.height <= 0) return;
  for (const face of currentFaces) {
    try {
      const b = face.box || face.boundingBox || face;
      const x = Math.max(0, Math.min(1, b.x || 0));
      const y = Math.max(0, Math.min(1, b.y || 0));
      const w = Math.max(0, Math.min(1, b.width || 0));
      const h = Math.max(0, Math.min(1, b.height || 0));
      const left = rect.left + Math.round(x * rect.width);
      const top = rect.top + Math.round(y * rect.height);
      const pxw = Math.round(w * rect.width);
      const pxh = Math.round(h * rect.height);
      const box = document.createElement('div');
      box.className = 'face-box';
      box.style.left = left + 'px';
      box.style.top = top + 'px';
      box.style.width = pxw + 'px';
      box.style.height = pxh + 'px';
      box.tabIndex = 0;
      box.dataset.faceId = face.faceId || face.id || '';

      const pid = face.identifiedPersonId || null;
      const inferredPid = (!pid && (face.inferredIdentifiedPersonId || null)) ? (face.inferredIdentifiedPersonId || null) : null;
      if (pid) {
        const { first, full } = personsName(pid);
        if (full) box.title = full;
        if (first) {
          const chip = document.createElement('div');
          chip.className = 'name-chip';
          chip.textContent = first;
          box.appendChild(chip);
        }
      } else if (inferredPid) {
        const { first, full } = personsName(inferredPid);
        if (full) box.title = `${full} (inferred)`;
        if (first) {
          const chip = document.createElement('button');
          chip.type = 'button';
          chip.className = 'name-chip inferred';
          chip.title = full ? `${full} (click to confirm)` : 'Click to confirm';
          chip.textContent = `${first}?`;
          chip.tabIndex = 0;
          const confirm = async (e) => {
            try {
              e?.preventDefault?.(); e?.stopPropagation?.();
              await api.setFacePerson(face.faceId || face.id, inferredPid);
              const idx = currentFaces.findIndex(f => (f.faceId||f.id) === (face.faceId||face.id));
              if (idx >= 0) { currentFaces[idx].identifiedPersonId = inferredPid; currentFaces[idx].inferredIdentifiedPersonId = null; }
              else { face.identifiedPersonId = inferredPid; face.inferredIdentifiedPersonId = null; }
              try { pushRecentPersonId(inferredPid); } catch {}
              showSuccess('Confirmed inferred person for this face');
              await ensurePersonsCache();
              renderFaces();
            } catch (err) {
              console.error('Failed to confirm inferred person', err);
              const details = err?.response?.data?.message || err?.message || '';
              showError(`Failed to confirm inferred person${details ? ': ' + details : ''}`);
            }
          };
          chip.addEventListener('click', confirm);
          chip.addEventListener('keydown', (ev) => { if (ev.key === 'Enter' || ev.key === ' ') { confirm(ev); } });
          box.appendChild(chip);
        }
      }

      const eb = document.createElement('button');
      eb.className = 'fb-edit';
      eb.type = 'button';
      eb.title = 'Edit identification';
      eb.textContent = '✎';
      eb.addEventListener('click', (e) => { e.stopPropagation(); openFaceEditModal(face); });
      box.appendChild(eb);

      ov.appendChild(box);
    } catch {}
  }
  try { updateConfirmAllButtonVisibility(); } catch {}
}

async function loadFacesForCurrentMedia() {
  const media = currentMedia; if (!media || !media.accessKey) { currentFaces = []; clearFacesOverlay(); return; }
  const seq = ++mediaFacesSeq;
  try {
    const facesList = await api.getMediaFaces(media.accessKey);
    if (seq !== mediaFacesSeq) return; // stale
    // facesList is OriginalFaces with facesIds
    const ids = Array.isArray(facesList?.facesIds) ? facesList.facesIds : [];
    const detailPromises = ids.map(id => api.getFace(id).catch(()=>null));
    const details = (await Promise.all(detailPromises)).filter(Boolean);
    currentFaces = details;
    await ensurePersonsCache();
    renderFaces();
  } catch (e) {
    currentFaces = [];
    clearFacesOverlay();
  }
}

async function openFaceEditModal(face, options) {
  if (!face) return;
  if (document.querySelector('.modal-overlay')) return;
  await ensurePersonsCache();
  const overlay = document.createElement('div'); overlay.className = 'modal-overlay';
  const currentPid = face.identifiedPersonId || '';
  // Build options HTML (legacy select removed; using typeahead combobox)
  overlay.innerHTML = `
    <div class="modal" role="dialog" aria-modal="true" tabindex="-1">
      <header>
        <div>Identify person for face</div>
        <button class="close" title="Close" style="background:none;border:none;font-size:18px;cursor:pointer">✕</button>
      </header>
      <div class="content">
        <div class="row">
          <div>
            <label for="fp-person-input">Person</label>
            <div class="combo" role="combobox" aria-expanded="false" aria-owns="fp-person-list" aria-haspopup="listbox">
              <input type="text" id="fp-person-input" autocomplete="off" placeholder="Type a name to filter…" aria-autocomplete="list" aria-controls="fp-person-list" aria-activedescendant="" style="width:100%; padding:8px; border:1px solid #e5e7eb; border-radius:6px;">
              <input type="hidden" id="fp-person-id" value="${currentPid}">
              <div id="fp-person-list" class="combo-list" role="listbox" tabindex="-1"></div>
            </div>
            <p class="muted" style="font-size:12px;color:#6b7280;margin-top:6px">Pick a person to set/update identification, or use Remove to clear it. Use arrow keys ↑/↓ to navigate suggestions, Enter to select.</p>
          </div>
          <div class="recent-persons" id="fp-recents" aria-label="Recent persons">
            <label style="display:block">Recent</label>
            <div class="recent-list" id="fp-recents-list"></div>
            <p class="muted" style="font-size:12px;color:#6b7280;margin-top:6px">Quick select one of your last choices.</p>
          </div>
        </div>
      </div>
      <footer>
        <button type="button" class="cancel">Cancel</button>
        <button type="button" class="remove" ${currentPid ? '' : 'disabled title="No identified person to remove"'}>Remove</button>
        <button type="button" class="delete-face" title="Delete this face" style="background:#b91c1c;color:#fff;border:1px solid #991b1b;border-radius:6px;padding:6px 10px;">Delete face</button>
        <button type="button" class="use-chosen" title="Set selected person face thumbnail from this face" style="background:#059669;color:#fff;border:1px solid #047857;border-radius:6px;padding:6px 10px;">Use as chosen face</button>
        <button type="button" class="save" style="background:#2563eb;color:#fff;border:1px solid #1d4ed8;border-radius:6px;padding:6px 10px;">Save</button>
      </footer>
    </div>`;
  document.body.appendChild(overlay);
  const modal = overlay.querySelector('.modal');
  const close = () => { overlay.remove(); };
  overlay.addEventListener('click', (e) => { if (e.target === overlay) close(); });
  overlay.querySelector('button.close')?.addEventListener('click', close);
  overlay.querySelector('button.cancel')?.addEventListener('click', (e)=>{ e.preventDefault(); e.stopPropagation(); close(); });
  setTimeout(()=>{ (modal.querySelector('#fp-person-input')||modal).focus(); }, 0);

  // --- Typeahead combo wiring ---
  const input = modal.querySelector('#fp-person-input');
  const hidden = modal.querySelector('#fp-person-id');
  const list = modal.querySelector('#fp-person-list');
  const combo = modal.querySelector('.combo');
  const saveBtn = overlay.querySelector('button.save');
  const useChosenBtn = overlay.querySelector('button.use-chosen');
  const removeBtn = overlay.querySelector('button.remove');
  const deleteBtn = overlay.querySelector('button.delete-face');

  function personLabel(p) { return `${p.firstName||''} ${p.lastName||''}`.trim() || p.id || ''; }
  const personsIndex = Array.from(personsCache?.values?.()||[]).map(p => ({ id: p.id, first: (p.firstName||'').trim(), last: (p.lastName||'').trim(), label: personLabel(p) }));

  // --- Recent persons quick-pick buttons ---
  try {
    const recWrap = modal.querySelector('#fp-recents');
    const recList = modal.querySelector('#fp-recents-list');
    if (recWrap && recList) {
      const recIds = getRecentPersonsIds();
      const recPersons = recIds
        .map(id => personsCache?.get?.(id))
        .filter(p => p && p.id)
        .slice(0, 10);
      if (recPersons.length === 0) {
        recWrap.style.display = 'none';
      } else {
        recWrap.style.display = '';
        recList.innerHTML = recPersons.map(p => {
          const label = personLabel(p).replace(/&/g,'&amp;').replace(/</g,'&lt;');
          return `<button type="button" class="recent-pill" data-id="${p.id}" title="Quick select ${label}">${label}</button>`;
        }).join('');
        recList.querySelectorAll('button.recent-pill').forEach(btn => {
          btn.addEventListener('click', async (e) => {
            e.preventDefault(); e.stopPropagation();
            const personId = btn.getAttribute('data-id');
            try {
              await api.setFacePerson(face.faceId || face.id, personId);
              const idx = currentFaces.findIndex(f => (f.faceId||f.id) === (face.faceId||face.id));
              if (idx >= 0) currentFaces[idx].identifiedPersonId = personId; else face.identifiedPersonId = personId;
              pushRecentPersonId(personId);
              showSuccess('Face identification saved');
              try { renderFaces(); } catch {}
              try { options && typeof options.onChanged === 'function' && options.onChanged({ ...face, identifiedPersonId: personId, inferredIdentifiedPersonId: null }); } catch {}
              // Close modal
              try { overlay.remove(); } catch {}
            } catch (err) {
              console.error('Quick-pick failed', err);
              showError('Failed to save identification');
            }
          });
        });
      }
    }
  } catch {}

  function updateButtons() {
    const pid = (hidden.value||'').trim();
    if (saveBtn) saveBtn.disabled = !pid;
    if (useChosenBtn) useChosenBtn.disabled = !pid;
  }
  updateButtons();

  // Initialize input from current selection if any
  if (hidden.value) {
    const p = personsIndex.find(x => x.id === hidden.value);
    if (p && input) input.value = p.label;
  }

  let activeIndex = -1;
  let currentItems = [];
  const MAX_ITEMS = 50;

  function renderList(items) {
    currentItems = items.slice(0, MAX_ITEMS);
    list.innerHTML = currentItems.map((it, idx) => `<div class="combo-option" role="option" id="fp-opt-${idx}" data-id="${it.id}" aria-selected="${idx===activeIndex?'true':'false'}">${it.label}</div>`).join('');
    // Wire click handlers
    list.querySelectorAll('.combo-option').forEach((el, idx) => {
      el.addEventListener('mousedown', (e) => { // mousedown to avoid input blur before click
        e.preventDefault();
        chooseIndex(idx);
      });
    });
  }

  function openList() { combo?.setAttribute('aria-expanded','true'); }
  function closeList() { combo?.setAttribute('aria-expanded','false'); activeIndex = -1; }

  function filterItems(q) {
    const s = (q||'').trim().toLowerCase();
    if (!s) return personsIndex;
    return personsIndex.filter(p => p.first.toLowerCase().includes(s) || p.last.toLowerCase().includes(s) || p.label.toLowerCase().includes(s));
  }

  function setSelectionById(id) {
    hidden.value = id || '';
    updateButtons();
  }

  function chooseIndex(idx) {
    if (idx < 0 || idx >= currentItems.length) return;
    const it = currentItems[idx];
    if (input) input.value = it.label;
    setSelectionById(it.id);
    closeList();
  }

  let debounceTimer = null;
  function scheduleFilter() {
    if (debounceTimer) clearTimeout(debounceTimer);
    debounceTimer = setTimeout(() => {
      const q = input.value;
      const items = filterItems(q);
      openList();
      activeIndex = -1;
      renderList(items);
      // If the typed value matches exactly one label, auto-select id but keep list open
      const exact = personsIndex.find(p => p.label.toLowerCase() === (q||'').trim().toLowerCase());
      if (exact) setSelectionById(exact.id); else setSelectionById('');
    }, 80);
  }

  input.addEventListener('input', scheduleFilter);
  input.addEventListener('focus', scheduleFilter);
  input.addEventListener('keydown', (e) => {
    const expanded = combo?.getAttribute('aria-expanded') === 'true';
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      if (!expanded) { openList(); renderList(filterItems(input.value)); }
      activeIndex = Math.min((activeIndex < 0 ? -1 : activeIndex) + 1, currentItems.length - 1);
      renderList(currentItems);
      const activeEl = document.getElementById(`fp-opt-${activeIndex}`);
      if (activeEl) { activeEl.scrollIntoView({ block: 'nearest' }); input.setAttribute('aria-activedescendant', activeEl.id); }
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      if (!expanded) { openList(); renderList(filterItems(input.value)); }
      activeIndex = Math.max(activeIndex - 1, 0);
      renderList(currentItems);
      const activeEl = document.getElementById(`fp-opt-${activeIndex}`);
      if (activeEl) { activeEl.scrollIntoView({ block: 'nearest' }); input.setAttribute('aria-activedescendant', activeEl.id); }
    } else if (e.key === 'Enter') {
      if (expanded && activeIndex >= 0) {
        e.preventDefault();
        chooseIndex(activeIndex);
      }
    } else if (e.key === 'Escape') {
      if (expanded) { e.preventDefault(); closeList(); }
    }
  });

  document.addEventListener('click', (e) => {
    if (!overlay.contains(e.target)) return; // outer already handled by overlay click
    if (combo && !combo.contains(e.target)) closeList();
  });

  // Use as chosen face
  overlay.querySelector('button.use-chosen')?.addEventListener('click', async () => {
    const personId = (hidden.value||'').trim();
    if (!personId) { showWarning('Please select a person first'); return; }
    try {
      await api.updatePersonFace(personId, face.faceId || face.id);
      // Optimistically update cache
      const p = personsCache?.get?.(personId);
      if (p) { p.chosenFaceId = face.faceId || face.id; }
      try { pushRecentPersonId(personId); } catch {}
      showSuccess('Set as chosen face for the selected person');
    } catch (e) {
      showError('Failed to set chosen face');
    }
  });

  // Save (set/update)
  overlay.querySelector('button.save')?.addEventListener('click', async () => {
    try {
      const personId = (hidden.value || '').trim();
      if (!personId) { showWarning('Please select a person'); return; }
      await api.setFacePerson(face.faceId || face.id, personId);
      // Update local state and re-render
      const idx = currentFaces.findIndex(f => (f.faceId||f.id) === (face.faceId||face.id));
      if (idx >= 0) currentFaces[idx].identifiedPersonId = personId; else face.identifiedPersonId = personId;
      try { pushRecentPersonId(personId); } catch {}
      showSuccess('Face identification updated');
      try { await ensurePersonsCache(); } catch {}
      try { renderFaces(); } catch {}
      try { options && typeof options.onChanged === 'function' && options.onChanged({ ...face, identifiedPersonId: personId, inferredIdentifiedPersonId: null }); } catch {}
      close();
    } catch (e) {
      showError('Failed to update face identification');
    }
  });

  // Remove identification only
  overlay.querySelector('button.remove')?.addEventListener('click', async () => {
    if (!face.identifiedPersonId) return; // safety
    try {
      await api.removeFacePerson(face.faceId || face.id);
      const idx = currentFaces.findIndex(f => (f.faceId||f.id) === (face.faceId||face.id));
      if (idx >= 0) currentFaces[idx].identifiedPersonId = null; else face.identifiedPersonId = null;
      showSuccess('Face identification removed');
      try { renderFaces(); } catch {}
      try { options && typeof options.onChanged === 'function' && options.onChanged({ ...face, identifiedPersonId: null }); } catch {}
      close();
    } catch (e) {
      showError('Failed to remove face identification');
    }
  });

  // Delete face (destructive)
  overlay.querySelector('button.delete-face')?.addEventListener('click', async () => {
    const fid = face.faceId || face.id;
    if (!fid) { close(); return; }
    try {
      await api.deleteFace(fid);
      // Remove from currentFaces (Viewer)
      try {
        const i = currentFaces.findIndex(f => (f.faceId||f.id) === fid);
        if (i >= 0) currentFaces.splice(i, 1);
      } catch {}
      try { renderFaces(); } catch {}
      // Notify caller (Persons faces grid) so it can update its list
      try { options && typeof options.onDeleted === 'function' && options.onDeleted(fid); } catch {}
      showSuccess('Face deleted');
      close();
    } catch (e) {
      console.error('Failed to delete face', e);
      showError('Failed to delete face');
    }
  });
}

function setFacesEnabled(state) {
  facesEnabled = !!state;
  try { localStorage.setItem('viewer.facesEnabled', facesEnabled ? '1' : '0'); } catch {}
  const btn = document.getElementById('btn-faces');
  if (btn) { btn.classList.toggle('active', facesEnabled); btn.setAttribute('aria-pressed', facesEnabled ? 'true' : 'false'); btn.title = facesEnabled ? 'Hide faces' : 'Show faces'; }
  if (facesEnabled) {
    loadFacesForCurrentMedia();
  } else {
    currentFaces = [];
    clearFacesOverlay();
  }
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
  // Faces toggle wiring
  const facesBtn = document.getElementById('btn-faces');
  if (facesBtn && !facesBtn.__wired) {
    facesBtn.addEventListener('click', () => setFacesEnabled(!facesEnabled));
    facesBtn.__wired = true;
  }
  // Restore persisted faces toggle
  try { const saved = localStorage.getItem('viewer.facesEnabled'); if (saved != null) facesEnabled = saved === '1'; } catch {}
  setFacesEnabled(facesEnabled);

  // Image click navigation disabled to avoid conflicts with other operations
  const imgContainer = document.querySelector('.image-container');
  let clickTimer = null;
  // Re-render faces overlay on window resize
  window.addEventListener('resize', () => { if (facesEnabled) { try { renderFaces(); } catch {} } });


  // Add Face drawing event listeners
  imgContainer?.addEventListener('mousedown', handleAddFacePointerDown);
  imgContainer?.addEventListener('mousemove', handleAddFacePointerMove);
  // Mouseup handled at window level to avoid duplicate events
  // Also listen on window to catch mouseup outside container
  window.addEventListener('mouseup', (e) => { if (isAddFaceModeActive()) handleAddFacePointerUp(e); });

  // Swap image source when entering/exiting fullscreen: original in fullscreen, normalized otherwise
  document.addEventListener('fullscreenchange', () => {
    try {
      const img = document.getElementById('main-image');
      const cont = document.querySelector('.image-container');
      if (!img || !currentMedia) return;
      const isFs = !!(document.fullscreenElement && cont && document.fullscreenElement === cont);
      const newUrl = (isFs ? api.mediaOriginalUrl(currentMedia.accessKey) : api.mediaNormalizedUrl(currentMedia.accessKey)) + `?t=${Date.now()}`;
      // Fade and restart zoom timing on the newly displayed variant
      try { img.style.opacity = '0'; } catch {}
      img.onload = () => {
        try { img.style.opacity = '1'; } catch {}
        try {
          const durActive = document.querySelector('#ss-duration button.active') || document.querySelector('#ss-duration button[data-secs="20"]');
          const secs = parseInt(durActive?.dataset?.secs || '20', 10) || 20;
          img.style.setProperty('--viewer-zoom-duration', `${secs}s`);
        } catch {}
        if (slideshowPlaying) {
          img.classList.remove('zooming');
          void img.offsetWidth;
          img.classList.add('zooming');
        } else {
          img.classList.remove('zooming');
        }
        if (facesEnabled) setTimeout(renderFaces, 0);
        img.onload = null;
      };
      img.src = newUrl;
      if (facesEnabled) setTimeout(renderFaces, 0);
    } catch {}
  });

  // Slideshow helpers using setTimeout so new settings are applied at each tick
  function stopSlideshow() {
    slideshowPlaying = false;
    if (slideshowTimer) { clearTimeout(slideshowTimer); slideshowTimer = null; }
    const btn = $('#btn-play'); if (btn) btn.textContent = '▷';
    // Ensure zoom effect is stopped when slideshow is paused/stopped
    try { const img = document.getElementById('main-image'); if (img) img.classList.remove('zooming'); } catch {}
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
    // Start or restart zoom on the currently displayed image immediately
    try {
      const img = document.getElementById('main-image');
      if (img) {
        const durActive = document.querySelector('#ss-duration button.active') || document.querySelector('#ss-duration button[data-secs="20"]');
        const secs = parseInt(durActive?.dataset?.secs || '20', 10) || 20;
        img.style.setProperty('--viewer-zoom-duration', `${secs}s`);
        img.classList.remove('zooming');
        void img.offsetWidth;
        img.classList.add('zooming');
      }
    } catch {}
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
      case ' ':
      case 'Space':
      case 'Spacebar': {
        const btn = document.getElementById('btn-play');
        if (btn && typeof btn.click === 'function') btn.click();
        handled = true; break;
      }
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

  // Ensure the Viewer tab keeps keyboard focus for navigation
  try {
    const viewerSec = document.getElementById('tab-viewer');
    if (viewerSec && !viewerSec.__focusWired) {
      try { viewerSec.setAttribute('tabindex', '0'); } catch {}
      try { viewerSec.setAttribute('aria-label', 'Viewer'); } catch {}
      const focusViewer = () => { try { viewerSec.focus({ preventScroll: true }); } catch { try { viewerSec.focus(); } catch {} } };
      // Refocus when pointer enters/moves/wheels over the Viewer area
      viewerSec.addEventListener('mouseenter', focusViewer, { passive: true });
      viewerSec.addEventListener('mousemove', focusViewer, { passive: true });
      viewerSec.addEventListener('wheel', focusViewer, { passive: true });
      // Also refocus when interacting with the inner image container to avoid focus being taken by buttons/controls
      const imgCont = document.querySelector('#tab-viewer .image-container');
      if (imgCont) {
        imgCont.addEventListener('mouseenter', focusViewer, { passive: true });
        imgCont.addEventListener('mousemove', focusViewer, { passive: true });
        imgCont.addEventListener('wheel', focusViewer, { passive: true });
        imgCont.addEventListener('click', focusViewer, { passive: true });
      }
      const sidebar = document.querySelector('#tab-viewer .sidebar');
      if (sidebar) {
        sidebar.addEventListener('mouseenter', focusViewer, { passive: true });
        sidebar.addEventListener('mousemove', focusViewer, { passive: true });
        sidebar.addEventListener('wheel', focusViewer, { passive: true });
      }
      viewerSec.__focusWired = true;
    }
  } catch {}

  // Keyboard scrolling for Events tab
  document.addEventListener('keydown', (e) => {
    const eventsActive = document.getElementById('tab-events')?.classList.contains('active');
    if (!eventsActive) return;
    const t = e.target;
    if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.tagName === 'SELECT' || t.isContentEditable)) return;
    const sec = document.getElementById('tab-events');
    if (!sec) return;
    const line = 40; // px per arrow step
    let handled = false;
    switch (e.key) {
      case 'Home':
        sec.scrollTo({ top: 0, behavior: 'smooth' }); handled = true; break;
      case 'End':
        sec.scrollTo({ top: sec.scrollHeight, behavior: 'smooth' }); handled = true; break;
      case 'PageDown': {
        const by = Math.max(0, (sec.clientHeight || 400) - 40);
        sec.scrollBy({ top: by, left: 0, behavior: 'smooth' }); handled = true; break;
      }
      case 'PageUp': {
        const by = Math.max(0, (sec.clientHeight || 400) - 40);
        sec.scrollBy({ top: -by, left: 0, behavior: 'smooth' }); handled = true; break;
      }
      case 'ArrowDown':
        sec.scrollBy({ top: line, left: 0 }); handled = true; break;
      case 'ArrowUp':
        sec.scrollBy({ top: -line, left: 0 }); handled = true; break;
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
    setActiveTab('map');
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
  const pubVal = ev.publishedOn || '';
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
            <label style="margin-top:8px">Published On (URL)</label>
            <input type="url" id="ev-published" placeholder="https://example.com/album" value="${pubVal.replace(/&/g,'&amp;').replace(/"/g,'&quot;')}">
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
      await api.updateEvent(ev.id, body);
      // Build an updated event object locally (optimistic refresh)
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

async function goToEventsById(eventId) {
  try {
    setActiveTab('events');
    // Wait for events to load and the target tile to exist
    let tries = 0;
    let li = null;
    while (tries < 120) { // up to ~6s
      li = document.querySelector(`#events-list li[data-event-id="${eventId}"]`);
      if (li) break;
      await new Promise(r => setTimeout(r, 50));
      tries++;
    }
    if (!li) {
      // As a fallback, trigger a refresh then try once more quickly
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

let eventsLoaded = false;
function getSavedEventsScrollTop() { try { return parseInt(localStorage.getItem('events.scrollTop')||'0',10)||0; } catch { return 0; } }
function setSavedEventsScrollTop(v) { try { localStorage.setItem('events.scrollTop', String(Math.max(0, v|0))); } catch {} }
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
function ensureEventsLoaded() { try { if (!eventsLoaded) { loadEvents({ force: false }); } } catch {} }

async function loadEvents(options = {}) { const { force = false } = options;
  const list = $('#events-list');
  const tabSection = document.getElementById('tab-events');
  if (!force && eventsLoaded && list && list.children && list.children.length > 0) {
    try { restoreEventsScrollState(); } catch {}
    return;
  }
  list.innerHTML = '';
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
    eventsLoaded = true;
    setTimeout(() => { try { restoreEventsScrollState(); } catch {} }, 0);
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
      await loadEvents({ force: true });
    } catch (e) {
      showError('Failed to create event');
    }
  });
}

function initEventsTab() {
  const refreshBtn = document.getElementById('refresh-events');
  if (refreshBtn && !refreshBtn.__wired) {
    refreshBtn.addEventListener('click', () => loadEvents({ force: true }));
    refreshBtn.__wired = true;
  }
  const createBtn = document.getElementById('create-event');
  if (createBtn && !createBtn.__wired) { createBtn.addEventListener('click', () => openEventCreateModal()); createBtn.__wired = true; }
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
    // Initial save to initialize state
    setTimeout(saveNow, 0);
    sec.__scrollWired = true;
  }
  // Make the Events section focusable and keep focus for keyboard scrolling
  if (sec && !sec.__focusWired) {
    try { sec.setAttribute('tabindex', '0'); } catch {}
    try { sec.setAttribute('aria-label', 'Events'); } catch {}
    const focusEvents = () => { try { sec.focus({ preventScroll: true }); } catch { try { sec.focus(); } catch {} } };
    // Refocus when pointer enters/moves/wheels over the Events area
    sec.addEventListener('mouseenter', focusEvents, { passive: true });
    sec.addEventListener('mousemove', focusEvents, { passive: true });
    sec.addEventListener('wheel', focusEvents, { passive: true });
    // Also refocus when interacting with the inner list area
    const list = document.getElementById('events-list');
    if (list) {
      list.addEventListener('mouseenter', focusEvents, { passive: true });
      list.addEventListener('mousemove', focusEvents, { passive: true });
      list.addEventListener('wheel', focusEvents, { passive: true });
    }
    sec.__focusWired = true;
  }
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
  // Shared tooltip utilities for mosaic tiles
  if (!window.__mosaicPhotoTooltip__) {
    const tip = document.createElement('div');
    tip.className = 'mosaic-photo-tooltip';
    tip.style.position = 'fixed';
    tip.style.left = '0px';
    tip.style.top = '0px';
    tip.style.display = 'none';
    document.body.appendChild(tip);
    window.__mosaicPhotoTooltip__ = tip;
    window.__mosaicPhotoTooltipHideTimer__ = null;
    window.__mosaicPhotoTooltipIdleTimer__ = null;
  }
  function hidePhotoTooltip(immediate = false) {
    const tip = window.__mosaicPhotoTooltip__;
    if (!tip) return;
    tip.classList.remove('show');
    const doHide = () => { tip.style.display = 'none'; };
    if (immediate) { doHide(); return; }
    setTimeout(doHide, 140);
  }
  function showPhotoTooltip(html, x, y) {
    const tip = window.__mosaicPhotoTooltip__;
    if (!tip) return;
    tip.innerHTML = html;
    tip.style.display = 'block';
    // Positioning: decide left or right of cursor based on viewport
    const padding = 10;
    const vw = window.innerWidth || document.documentElement.clientWidth || 1024;
    const vh = window.innerHeight || document.documentElement.clientHeight || 768;
    tip.style.maxWidth = Math.floor(vw * 0.6) + 'px';
    // Temporarily set to compute size
    tip.style.left = '0px'; tip.style.top = '0px';
    tip.classList.add('show');
    const rect = tip.getBoundingClientRect();
    const placeRight = x < vw * 0.55; // if near left/middle, place to right; else to left
    let left = placeRight ? (x + 14) : (x - rect.width - 14);
    let top = y + 12;
    // keep within viewport
    if (left < padding) left = padding;
    if (left + rect.width + padding > vw) left = vw - rect.width - padding;
    if (top + rect.height + padding > vh) top = Math.max(padding, y - rect.height - 12);
    tip.style.left = Math.floor(left) + 'px';
    tip.style.top = Math.floor(top) + 'px';
  }
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
  // Touch support: detect tap to open viewer; also upgrade to normalized image
  const __touch = { x: 0, y: 0, t: 0, moved: false };
  tile.addEventListener('touchstart', (e) => {
    try {
      if (!e.touches || e.touches.length !== 1) return;
      const t = e.touches[0];
      __touch.x = t.clientX; __touch.y = t.clientY; __touch.t = Date.now(); __touch.moved = false;
      // Show normalized layer as user touches the tile
      showNormalized();
      // Allow parent mosaic touch handlers to process drag for scrolling; do not stopPropagation here
    } catch {}
  }, { passive: true });
  tile.addEventListener('touchmove', (e) => {
    try {
      if (!e.touches || e.touches.length !== 1) return;
      const t = e.touches[0];
      const dx = Math.abs(t.clientX - __touch.x);
      const dy = Math.abs(t.clientY - __touch.y);
      if (dx > 10 || dy > 10) __touch.moved = true;
      // Allow parent mosaic touch handlers to process drag for scrolling; do not stopPropagation here
    } catch {}
  }, { passive: true });
  tile.addEventListener('touchend', async (e) => {
    try {
      e.stopPropagation();
      const dt = Date.now() - (__touch.t || Date.now());
      const isTap = !__touch.moved && dt < 500;
      if (!isTap) return;
      // Treat as click: open in viewer using cached media when possible
      e.preventDefault();
      const cached = (media && media.accessKey) ? (mosaicMediaCache.get(media.accessKey) || tile.__media) : null;
      const fullMedia = (cached && cached.accessKey) ? cached : await api.getMediaByKey(media.accessKey);
      setActiveTab('viewer');
      showMedia(fullMedia);
    } catch (err) {
      console.warn('Touch tap open failed', err);
    }
  }, { passive: false });

  // Enhanced tooltip logic: show event name + timestamp when mouse stops moving
  // and auto-hide a few seconds after movement resumes. Position near cursor with viewport clamping.
  try { tile.title = ''; } catch {}
  const IDLE_MS = 500; // delay before showing when still
  const HIDE_AFTER_MOVE_MS = 2200; // auto-hide a few seconds after movement resumes
  let lastMouse = { x: 0, y: 0 };
  let idleTimer = null;
  let hideTimer = null;
  let tipVisible = false;
  let lastMoveAt = 0;

  function buildTooltipHtml() {
    const tsStr = mediaTimestamp(media);
    const ts = tsStr ? new Date(tsStr) : null;
    const tsHuman = ts && !isNaN(ts.getTime())
      ? ts.toLocaleString(undefined, { year:'numeric', month:'short', day:'numeric', hour:'2-digit', minute:'2-digit', second:'2-digit' })
      : '';
    const ev0 = Array.isArray(media?.events) && media.events.length > 0 ? media.events[0] : null;
    const evName = ev0 && ev0.name ? ev0.name : '';
    const title = evName || '(no event)';
    const subtitle = tsHuman || '';
    return `<div class="title">${title.replace(/&/g,'&amp;').replace(/</g,'&lt;')}</div>`+
           (subtitle ? `<div class="subtitle">${subtitle}</div>` : '');
  }

  const onMouseMove = (e) => {
    lastMouse = { x: e.clientX, y: e.clientY };
    lastMoveAt = performance.now();
    // If tooltip is visible, schedule auto-hide after movement
    if (tipVisible) {
      if (hideTimer) { clearTimeout(hideTimer); hideTimer = null; }
      hideTimer = setTimeout(() => { hidePhotoTooltip(false); tipVisible = false; }, HIDE_AFTER_MOVE_MS);
    }
    // Reset idle timer to show tooltip after mouse becomes still
    if (idleTimer) { clearTimeout(idleTimer); idleTimer = null; }
    idleTimer = setTimeout(() => {
      // Check that mouse is still (no moves since timer set)
      const now = performance.now();
      if (now - lastMoveAt >= IDLE_MS - 10) {
        const html = buildTooltipHtml();
        if (html) { showPhotoTooltip(html, lastMouse.x, lastMouse.y); tipVisible = true; }
      }
    }, IDLE_MS);
  };
  const onMouseLeave = () => {
    if (idleTimer) { clearTimeout(idleTimer); idleTimer = null; }
    if (hideTimer) { clearTimeout(hideTimer); hideTimer = null; }
    if (tipVisible) { hidePhotoTooltip(true); tipVisible = false; }
  };
  tile.addEventListener('mousemove', onMouseMove, { passive: true });
  tile.addEventListener('mouseleave', onMouseLeave, { passive: true });
  tile.addEventListener('mouseenter', onMouseMove, { passive: true });
  // On touch, we won't show the tooltip to avoid conflicts with tap actions

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

      // Timeline hover → show precise date tooltip and drag-to-scroll behavior
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
          // Also move the blue cursor while hovering/dragging
          try { updateTimelineCursor(ts); } catch {}
        };
        timelineEl.addEventListener('mousemove', updateTooltip);
        timelineEl.addEventListener('mouseenter', updateTooltip);
        timelineEl.addEventListener('mouseleave', () => {
          const tip = timelineEl.querySelector('#mosaic-timeline-tooltip');
          if (tip) tip.classList.remove('show');
        });

        // Drag-to-scroll through the timeline
        if (!timelineEl.__dragWired) {
          const posToTs = (clientY) => {
            const rect = timelineEl.getBoundingClientRect();
            const yPx = (clientY - rect.top);
            const ratio = Math.max(0, Math.min(1, yPx / Math.max(1, rect.height)));
            return scrollPositionToTimestamp(ratio, mosaicOldestTimestamp, mosaicNewestTimestamp);
          };
          const state = { active: false, lastTs: null, rafPending: false };
          const onMoveDoc = (ev) => {
            if (!state.active) return;
            const clientY = (ev.touches && ev.touches.length) ? ev.touches[0].clientY : ev.clientY;
            const ts = posToTs(clientY);
            state.lastTs = ts;
            updateTooltip({ clientY });
            if (!state.rafPending) {
              state.rafPending = true;
              requestAnimationFrame(async () => {
                state.rafPending = false;
                if (!state.active || !state.lastTs) return;
                try { await refreshMosaicAtTimestamp(state.lastTs); } catch {}
              });
            }
          };
          const onUpDoc = () => {
            if (!state.active) return;
            state.active = false;
            try { document.removeEventListener('mousemove', onMoveDoc); } catch {}
            try { document.removeEventListener('mouseup', onUpDoc); } catch {}
            try { document.removeEventListener('touchmove', onMoveDoc); } catch {}
            try { document.removeEventListener('touchend', onUpDoc); } catch {}
            try { document.body.classList.remove('mosaic-dragging'); } catch {}
          };
          const onDown = (ev) => {
            ev.preventDefault();
            focusMosaic();
            state.active = true; state.lastTs = null;
            try { document.addEventListener('mousemove', onMoveDoc, { passive: true }); } catch {}
            try { document.addEventListener('mouseup', onUpDoc, { passive: true }); } catch {}
            try { document.addEventListener('touchmove', onMoveDoc, { passive: true }); } catch {}
            try { document.addEventListener('touchend', onUpDoc, { passive: true }); } catch {}
            try { document.body.classList.add('mosaic-dragging'); } catch {}
            const clientY = (ev.touches && ev.touches.length) ? ev.touches[0].clientY : ev.clientY;
            const ts = posToTs(clientY);
            // Initial refresh so content updates immediately
            refreshMosaicAtTimestamp(ts);
          };
          timelineEl.addEventListener('mousedown', onDown);
          timelineEl.addEventListener('touchstart', onDown, { passive: false });
          timelineEl.__dragWired = true;
        }

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

      // Touch drag navigation for smartphones/tablets on the mosaic area
      if (tabSection.__mosaicTouchWired) {
        try { tabSection.removeEventListener('touchstart', tabSection.__mosaicTouchStart, { passive: false }); } catch {}
        try { tabSection.removeEventListener('touchmove', tabSection.__mosaicTouchMove, { passive: false }); } catch {}
        try { tabSection.removeEventListener('touchend', tabSection.__mosaicTouchEnd, { passive: false }); } catch {}
      }
      (function(){
        const touchState = { active: false, lastY: 0, accum: 0, raf: false };
        const stepThresholdPx = () => {
          const layout = computeMosaicLayout();
          return Math.max(40, Math.floor(layout.tileSize * 0.6)); // ~60% of a row height
        };
        const processAccum = () => {
          touchState.raf = false;
          const thr = stepThresholdPx();
          const acc = touchState.accum;
          if (!acc) return;
          const cols = computeMosaicLayout().columns || 1;
          const magnitude = Math.abs(acc);
          const rows = Math.floor(magnitude / thr);
          if (rows <= 0) return;
          const dir = acc < 0 ? 'previous' : 'next'; // finger up → negative dy → older
          const steps = Math.max(1, Math.min(cols * 8, rows * cols));
          // reduce accumulation by consumed pixels
          touchState.accum = acc + (dir === 'previous' ? rows * thr : -rows * thr);
          navigateMosaic(dir, steps);
        };
        const onTouchStart = (e) => {
          if (!e.touches || e.touches.length !== 1) return; // ignore multi-touch
          e.preventDefault();
          touchState.active = true;
          touchState.lastY = e.touches[0].clientY;
          touchState.accum = 0;
        };
        const onTouchMove = (e) => {
          if (!touchState.active || !e.touches || e.touches.length !== 1) return;
          e.preventDefault();
          const y = e.touches[0].clientY;
          const dy = y - touchState.lastY;
          touchState.lastY = y;
          touchState.accum += dy;
          if (!touchState.raf) { touchState.raf = true; requestAnimationFrame(processAccum); }
        };
        const onTouchEnd = (e) => {
          if (!touchState.active) return;
          e.preventDefault();
          touchState.active = false;
          // process any remaining accumulation
          processAccum();
          touchState.accum = 0;
        };
        tabSection.__mosaicTouchStart = onTouchStart;
        tabSection.__mosaicTouchMove = onTouchMove;
        tabSection.__mosaicTouchEnd = onTouchEnd;
        tabSection.addEventListener('touchstart', onTouchStart, { passive: false });
        tabSection.addEventListener('touchmove', onTouchMove, { passive: false });
        tabSection.addEventListener('touchend', onTouchEnd, { passive: false });
      })();

      // Mirror touch navigation handlers on the mosaic container to ensure mobile browsers
      // consistently prevent default viewport scrolling (pull-to-refresh, page scroll)
      (function(){
        const containerEl = document.getElementById('mosaic-container');
        if (!containerEl) return;
        if (containerEl.__mosaicTouchWired) return;
        const touchState = { active: false, lastY: 0, accum: 0, raf: false };
        const stepThresholdPx = () => {
          const layout = computeMosaicLayout();
          return Math.max(40, Math.floor(layout.tileSize * 0.6));
        };
        const processAccum = () => {
          touchState.raf = false;
          const thr = stepThresholdPx();
          const acc = touchState.accum;
          if (!acc) return;
          const cols = computeMosaicLayout().columns || 1;
          const magnitude = Math.abs(acc);
          const rows = Math.floor(magnitude / thr);
          if (rows <= 0) return;
          const dir = acc < 0 ? 'previous' : 'next'; // finger up → negative dy → older
          const steps = Math.max(1, Math.min(cols * 8, rows * cols));
          touchState.accum = acc + (dir === 'previous' ? rows * thr : -rows * thr);
          navigateMosaic(dir, steps);
        };
        const onTouchStart = (e) => {
          if (!e.touches || e.touches.length !== 1) return;
          e.preventDefault();
          touchState.active = true;
          touchState.lastY = e.touches[0].clientY;
          touchState.accum = 0;
        };
        const onTouchMove = (e) => {
          if (!touchState.active || !e.touches || e.touches.length !== 1) return;
          e.preventDefault();
          const y = e.touches[0].clientY;
          const dy = y - touchState.lastY;
          touchState.lastY = y;
          touchState.accum += dy;
          if (!touchState.raf) { touchState.raf = true; requestAnimationFrame(processAccum); }
        };
        const onTouchEnd = (e) => {
          if (!touchState.active) return;
          e.preventDefault();
          touchState.active = false;
          processAccum();
          touchState.accum = 0;
        };
        containerEl.addEventListener('touchstart', onTouchStart, { passive: false });
        containerEl.addEventListener('touchmove', onTouchMove, { passive: false });
        containerEl.addEventListener('touchend', onTouchEnd, { passive: false });
        containerEl.__mosaicTouchWired = true;
      })();

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

// Persons
let personsAll = [];
let personsFilter = '';

function comparePersons(a, b) {
  // Sort by lastName first, then firstName (case-insensitive, null-safe)
  const al = ((a?.lastName) || '').toLowerCase();
  const bl = ((b?.lastName) || '').toLowerCase();
  if (al < bl) return -1; if (al > bl) return 1;
  const af = ((a?.firstName) || '').toLowerCase();
  const bf = ((b?.firstName) || '').toLowerCase();
  if (af < bf) return -1; if (af > bf) return 1;
  return 0;
}

function matchesPersonFilter(p, q) {
  if (!q) return true;
  const s = q.toLowerCase();
  const fields = [p.firstName||'', p.lastName||'', p.description||''];
  for (const f of fields) { if (String(f).toLowerCase().includes(s)) return true; }
  return false;
}

function renderPersonsList() {
  const list = document.getElementById('persons-list'); if (!list) return; list.innerHTML = '';
  const filtered = personsAll.filter(p => matchesPersonFilter(p, personsFilter)).sort(comparePersons);

  // Lazy load chosen face thumbnails
  const limit = 6;
  let inFlight = 0;
  const pending = [];
  const scheduled = new WeakSet();

  async function resolveAndRenderPersonThumb(li, person) {
    inFlight++;
    try {
      if (!person.chosenFaceId) return;
      const img = new Image();
      img.src = api.faceImageUrl(person.chosenFaceId);
      img.alt = `${person.firstName} ${person.lastName}`;
      img.loading = 'lazy';
      img.decoding = 'async';
      img.style.width = '100%';
      img.style.height = '100%';
      img.style.objectFit = 'cover';
      img.style.display = 'block';
      img.onerror = () => { /* ignore */ };
      const ph = li.querySelector('.person-thumb');
      if (ph) { ph.innerHTML = ''; ph.style.background = 'transparent'; ph.appendChild(img); }
    } finally {
      inFlight--; schedulePersonThumb();
    }
  }

  function schedulePersonThumb() {
    while (inFlight < limit && pending.length > 0) {
      const item = pending.shift();
      resolveAndRenderPersonThumb(item.li, item.person);
    }
  }

  const tabSection = document.getElementById('tab-persons');
  const observer = ('IntersectionObserver' in window)
    ? new IntersectionObserver((entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting) {
            const li = entry.target; observer.unobserve(li);
            if (scheduled.has(li)) continue;
            const person = li.__person; scheduled.add(li);
            if (person && person.chosenFaceId) { pending.push({ li, person }); schedulePersonThumb(); }
          }
        }
      }, { root: tabSection, rootMargin: '200px 0px', threshold: 0.01 })
    : null;

  for (const p of filtered) {
    const li = document.createElement('li');
    li.__person = p;
    li.dataset.personId = p.id || '';
    // Make list item clearly clickable and add hover background
    li.style.cursor = 'pointer';
    li.style.borderRadius = '8px';
    li.style.padding = '6px';
    li.addEventListener('mouseenter', () => { li.__origBg = li.style.backgroundColor; li.style.backgroundColor = '#DBEAFE'; });
    li.addEventListener('mouseleave', () => { li.style.backgroundColor = li.__origBg || ''; });
    const birthStr = p.birthDate ? new Date(p.birthDate).toLocaleDateString() : '';
    const desc = p.description || '';
    const metaParts = [];
    if (birthStr) metaParts.push('birth: ' + birthStr);
    if (desc) metaParts.push(desc);
    const meta = metaParts.join(' • ');
    li.innerHTML = `
      <div style="display:flex; align-items:center; gap:12px;">
        <div class="person-thumb" style="width:60px;height:60px;border-radius:6px;background:#f3f4f6;display:flex;align-items:center;justify-content:center;overflow:hidden;color:#9ca3af;font-size:10px;flex-shrink:0;">${p.chosenFaceId ? 'Loading…' : 'No image'}</div>
        <div style="flex:1;">
          <h4 style="margin:0 0 4px 0;">${p.firstName} ${p.lastName}</h4>
          <div style="font-size:12px;color:#555">${meta}</div>
        </div>
        <button class="ev-edit-btn" title="Edit">✎ Edit</button>
        <button class="ev-del-btn" title="Delete">🗑 Delete</button>
      </div>`;
    list.appendChild(li);
    const editBtn = li.querySelector('.ev-edit-btn');
    if (editBtn) editBtn.onclick = (e) => { e.stopPropagation(); openPersonEditModal(p); };
    const delBtn = li.querySelector('.ev-del-btn');
    if (delBtn) delBtn.onclick = async (e) => {
      e.stopPropagation();
      if (!confirm(`Delete person ${p.firstName} ${p.lastName}?`)) return;
      try { await api.deletePerson(p.id); showSuccess('Person deleted'); await loadPersons(); }
      catch { showError('Failed to delete person'); }
    };
    // Navigate to person faces view when clicking the tile background
    li.addEventListener('click', () => openPersonFacesView(p));

    if (observer && p.chosenFaceId) observer.observe(li);
    else if (p.chosenFaceId) { pending.push({ li, person: p }); schedulePersonThumb(); }
  }
}

async function loadPersons() {
  const list = document.getElementById('persons-list'); if (!list) return; list.innerHTML = '';
  try {
    personsAll = await api.listPersons();
    renderPersonsList();
  } catch (e) { list.innerHTML = '<li>Failed to load persons</li>'; }
}

// Persons → Person Faces subview
async function openPersonFacesView(person) {
  const tab = document.getElementById('tab-persons'); if (!tab) return;
  // Hide default list and actions
  const actions = tab.querySelector('.list-actions'); const list = tab.querySelector('#persons-list');
  if (actions) actions.style.display = 'none'; if (list) list.style.display = 'none';
  // Create view container
  let view = tab.querySelector('.person-faces-view');
  if (view) view.remove();
  view = document.createElement('div');
  view.className = 'person-faces-view';
  view.innerHTML = `
    <div class="person-faces-header">
      <button type="button" class="back" title="Back to persons" aria-label="Back">← Back</button>
      <div class="title">${(person.firstName||'') + ' ' + (person.lastName||'')}</div>
      <div class="spacer"></div>
      <div class="pf-actions">
        <div class="pf-left-actions">
          <div class="pf-size" role="group" aria-label="Face size">
            <button type="button" class="pf-size-small" data-size="small" title="Small">Small</button>
            <button type="button" class="pf-size-medium" data-size="medium" title="Medium">Medium</button>
            <button type="button" class="pf-size-max" data-size="max" title="Maximum">Maximum</button>
          </div>
          <button type="button" class="confirm-all" title="Confirm all shown faces" style="display:none;border:1px solid #059669;background:#10b981;color:#fff;border-radius:6px;padding:6px 10px;">Confirm all</button>
          <button type="button" class="confirm-selected" title="Confirm selected faces" style="display:none;border:1px solid #059669;background:#10b981;color:#fff;border-radius:6px;padding:6px 10px;">Confirm selected</button>
        </div>
        <div class="pf-right-actions">
          <button type="button" class="toggle-validate" title="Switch validation mode" style="border:1px solid #1d4ed8;background:#2563eb;color:#fff;border-radius:6px;padding:6px 10px;">to validate</button>
        </div>
      </div>
    </div>
    <div class="person-faces-grid" id="person-faces-grid">
      <div class="status muted">Loading faces…</div>
    </div>
  `;
  tab.appendChild(view);
  const backBtn = view.querySelector('button.back');
  if (backBtn && !backBtn.__wired) {
    backBtn.addEventListener('click', () => {
      try { view.remove(); } catch {}
      if (actions) actions.style.display = '';
      if (list) { list.style.display = ''; try { list.scrollIntoView({ block: 'nearest' }); } catch {} }
    });
    backBtn.__wired = true;
  }
  // Load faces
  let faces = [];
  try {
    faces = await api.listPersonFaces(person.id);
    // Always sort by timestamp (newest first)
    faces.sort((a,b) => new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime());
  } catch (e) {
    const grid = view.querySelector('#person-faces-grid');
    if (grid) grid.innerHTML = `<div class="status error">Failed to load faces</div>`;
    return;
  }
  // Store state on view
  view.__allFaces = faces;
  view.__mode = 'identified'; // default mode
  view.__selected = new Set(); // selected face ids for validation

  // Wire actions
  const toggleBtn = view.querySelector('.toggle-validate');
  const confirmAllBtn = view.querySelector('.confirm-all');
  const confirmSelBtn = view.querySelector('.confirm-selected');
  const sizeCtl = view.querySelector('.pf-size');
  const sizeBtns = sizeCtl ? Array.from(sizeCtl.querySelectorAll('button')) : [];

  // Initialize face size from sessionStorage (default to 'max')
  const SIZE_STORAGE_KEY = 'personFaces.size';
  function getStoredSize() {
    try { return sessionStorage.getItem(SIZE_STORAGE_KEY) || 'max'; } catch { return 'max'; }
  }
  function setStoredSize(sz) {
    try { sessionStorage.setItem(SIZE_STORAGE_KEY, sz); } catch {}
  }
  function applySizeClass(sz) {
    // remove previous classes
    view.classList.remove('size-small', 'size-medium', 'size-max');
    const cls = sz === 'small' ? 'size-small' : (sz === 'medium' ? 'size-medium' : 'size-max');
    view.classList.add(cls);
    // update active button state
    sizeBtns.forEach(b => b.classList.toggle('active', b.getAttribute('data-size') === sz));
  }
  const initialSize = getStoredSize();
  applySizeClass(initialSize);
  // Wire size control buttons
  sizeBtns.forEach(btn => {
    if (btn.__wired) return;
    btn.addEventListener('click', (e) => {
      e.preventDefault(); e.stopPropagation();
      const sz = btn.getAttribute('data-size') || 'max';
      setStoredSize(sz);
      applySizeClass(sz);
      // No need to re-render; CSS grid reacts to class change. Keep header states in sync just in case.
      try { if (typeof view.__updateActions === 'function') view.__updateActions(); } catch {}
    });
    btn.__wired = true;
  });

  // Helper: refetch the latest faces for this person and refresh UI
  async function refetchFacesAndRefresh({ keepMode = true } = {}) {
    try {
      const fresh = await api.listPersonFaces(person.id);
      // Always sort by timestamp (newest first)
      fresh.sort((a,b) => new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime());
      view.__allFaces = fresh;
    } catch (e) {
      // Non-blocking notice; keep local state as fallback
      console.warn('Refetch faces failed:', e);
      showWarning('Unable to fully refresh faces; showing cached list');
    } finally {
      // Clear selection and refresh grid/buttons
      try { view.__selected = new Set(); } catch {}
      if (!keepMode) view.__mode = 'identified';
      try { refreshGrid(); } catch {}
      try { if (typeof view.__updateActions === 'function') view.__updateActions(); } catch {}
    }
  }

  // --- Local state helpers to avoid full reloads when not necessary ---
  function removeFromSelection(fid) {
    try { if (view.__selected && view.__selected.has(fid)) view.__selected.delete(fid); } catch {}
  }

  function removeFaceFromAllFacesById(fid) {
    const all = view.__allFaces || [];
    const idx = all.findIndex(f => (f.faceId || f.id) === fid);
    if (idx >= 0) all.splice(idx, 1);
  }

  function findFaceInAllFaces(fid) {
    const all = view.__allFaces || [];
    return all.find(f => (f.faceId || f.id) === fid) || null;
  }

  function localRefresh({ keepMode = true } = {}) {
    if (!keepMode) view.__mode = 'identified';
    // Re-render grid and update buttons without network roundtrip
    try { refreshGrid(); } catch {}
    try { if (typeof view.__updateActions === 'function') view.__updateActions(); } catch {}
  }

  // Helper: robust ID equality (handle number/string mismatches and nulls)
  function idEq(a, b) {
    const as = (a === undefined || a === null) ? '' : String(a);
    const bs = (b === undefined || b === null) ? '' : String(b);
    return as === bs;
  }

  function updateActionsVisibility() {
    const isValidate = view.__mode === 'validate';
    // Compute pending inferred faces for this person
    const all = view.__allFaces || [];
    const pendingCount = all.filter(f => !f.identifiedPersonId && idEq(f.inferredIdentifiedPersonId, person.id)).length;
    if (toggleBtn) {
      // Show pending count only when NOT in validation mode, and hide it when exiting
      if (isValidate) {
        toggleBtn.textContent = 'Exit validation';
      } else {
        toggleBtn.textContent = `to validate (${pendingCount})`;
      }
      // Keep a neutral/consistent style for the toggle button
      toggleBtn.style.border = '1px solid #1d4ed8';
      toggleBtn.style.background = '#2563eb';
      toggleBtn.style.color = '#fff';
      toggleBtn.style.borderRadius = '6px';
      toggleBtn.style.padding = '6px 10px';
      // Disable when there is nothing to validate and not in validation mode
      toggleBtn.disabled = (!isValidate && pendingCount === 0);
      toggleBtn.style.opacity = toggleBtn.disabled ? '0.6' : '1';
      toggleBtn.style.cursor = toggleBtn.disabled ? 'not-allowed' : 'pointer';
      toggleBtn.title = (!isValidate && pendingCount === 0)
        ? 'No faces to validate'
        : (isValidate ? 'Exit validation mode' : 'Switch to validation mode');
    }
    if (confirmAllBtn) {
      confirmAllBtn.style.display = isValidate ? '' : 'none';
      // Move the pending count indicator to the Confirm all button and manage disabled state
      confirmAllBtn.textContent = `Confirm all (${pendingCount})`;
      confirmAllBtn.disabled = !(isValidate && pendingCount > 0);
      confirmAllBtn.style.opacity = confirmAllBtn.disabled ? '0.6' : '1';
      confirmAllBtn.style.cursor = confirmAllBtn.disabled ? 'not-allowed' : 'pointer';
      confirmAllBtn.title = confirmAllBtn.disabled ? 'No inferred faces to confirm' : 'Confirm all shown inferred faces';
    }
    const selCount = (view.__selected && view.__selected.size) || 0;
    if (confirmSelBtn) {
      // Always show the button in validation mode, with a live count; disable when nothing selected
      confirmSelBtn.textContent = `Confirm selected (${selCount})`;
      confirmSelBtn.style.display = isValidate ? '' : 'none';
      confirmSelBtn.disabled = !(isValidate && selCount > 0);
      confirmSelBtn.style.opacity = confirmSelBtn.disabled ? '0.6' : '1';
      confirmSelBtn.style.cursor = confirmSelBtn.disabled ? 'not-allowed' : 'pointer';
      confirmSelBtn.title = confirmSelBtn.disabled ? 'Select one or more faces to enable' : 'Confirm the selected faces';
    }
  }

  // Expose header updater on the view so child renderers/handlers can invoke it safely
  view.__updateActions = updateActionsVisibility;

  function refreshGrid() {
    const all = view.__allFaces || [];
    if (view.__mode === 'validate') {
      const inferredOnly = all.filter(f => !f.identifiedPersonId && idEq(f.inferredIdentifiedPersonId, person.id));
      renderPersonFacesGrid(view, person, inferredOnly, { mode: 'validate' });
    } else {
      // Only show faces identified to THIS person
      const identifiedOnly = all.filter(f => idEq(f.identifiedPersonId, person.id));
      renderPersonFacesGrid(view, person, identifiedOnly, { mode: 'identified' });
    }
    updateActionsVisibility();
  }

  // Expose a safe refresh hook for child handlers (e.g., inside grid renderers)
  view.__refreshGrid = refreshGrid;

  if (toggleBtn && !toggleBtn.__wired) {
    toggleBtn.addEventListener('click', () => {
      if (toggleBtn.disabled) return;
      view.__mode = (view.__mode === 'validate') ? 'identified' : 'validate';
      view.__selected = new Set();
      refreshGrid();
    });
    toggleBtn.__wired = true;
  }

  async function confirmFaces(faceIds) {
    if (!faceIds || faceIds.length === 0) return;
    try {
      // Confirm sequentially to keep it simple and robust
      for (const fid of faceIds) {
        await api.setFacePerson(fid, person.id);
        // Local state update per face using the current view cache
        const inCache = (view && Array.isArray(view.__allFaces))
          ? (view.__allFaces.find(f => (f.faceId || f.id) === fid) || null)
          : null;
        if (inCache) { inCache.identifiedPersonId = person.id; inCache.inferredIdentifiedPersonId = null; }
        removeFromSelection(fid);
      }
      try { pushRecentPersonId(person.id); } catch {}
      showSuccess(`Confirmed ${faceIds.length} face(s)`);
    } catch (e) {
      const details = e?.response?.data?.message || e?.response?.data?.error || e?.message || '';
      showError(`Failed to confirm faces${details ? ': ' + details : ''}`);
    } finally {
      // Local refresh is enough: faces will move out of validation list immediately
      localRefresh({ keepMode: true });
    }
  }

  if (confirmAllBtn && !confirmAllBtn.__wired) {
    confirmAllBtn.addEventListener('click', () => {
      const gridFaces = (view.__mode === 'validate')
        ? (view.__allFaces||[]).filter(f => !f.identifiedPersonId && idEq(f.inferredIdentifiedPersonId, person.id))
        : [];
      const ids = gridFaces.map(f => f.faceId || f.id);
      if (ids.length === 0) return;
      const pname = `${person.firstName || ''} ${person.lastName || ''}`.trim() || 'this person';
      const msg = `Confirm all ${ids.length} inferred face(s) for ${pname}?`;
      if (!confirm(msg)) return;
      confirmFaces(ids);
    });
    confirmAllBtn.__wired = true;
  }

  if (confirmSelBtn && !confirmSelBtn.__wired) {
    confirmSelBtn.addEventListener('click', () => {
      const ids = Array.from(view.__selected || []);
      if (ids.length === 0) return;
      const pname = `${person.firstName || ''} ${person.lastName || ''}`.trim() || 'this person';
      const msg = `Confirm ${ids.length} selected face(s) for ${pname}?`;
      if (!confirm(msg)) return;
      confirmFaces(ids);
    });
    confirmSelBtn.__wired = true;
  }

  // First render (default identified-only)
  refreshGrid();
}

async function goToViewerForFace(face) {
  try {
    const originalId = face.originalId || (face.original && face.original.id);
    if (!originalId) { showWarning('No linked photo for this face'); return; }
    const st = await api.getState(originalId);
    const key = (st && (st.mediaAccessKey || st.accessKey)) || null;
    const accessKey = key || (st && st.media && st.media.accessKey) || null;
    if (!accessKey) { showWarning('Unable to resolve photo for this face'); return; }
    const media = await api.getMediaByKey(accessKey);
    setActiveTab('viewer');
    showMedia(media);
  } catch (e) {
    console.warn('goToViewerForFace failed', e);
    showError('Failed to open the viewer for this face');
  }
}
function renderPersonFacesGrid(view, person, faces, opts) {
  const grid = view.querySelector('#person-faces-grid'); if (!grid) return;
  if (!faces || faces.length === 0) { grid.innerHTML = '<div class="status muted">No faces found for this person</div>'; return; }
  const nodes = [];
  const mode = (opts && opts.mode) || 'identified';
  // Selection handling only in validation mode
  // Always use the canonical selection stored on the view to avoid stale references
  if (!view.__selected) view.__selected = new Set();
  let lastSelectedIndex = -1;
  let isDraggingSelect = false;
  let dragIntentAdd = null; // null = not dragging; true = add during drag; false = remove during drag

  function setSelected(fid, on) {
    // Always mutate the canonical selection set stored on the view
    const sel = view.__selected;
    if (!sel) return;
    if (on) sel.add(fid); else sel.delete(fid);
  }

  function applyDraggingHandlers(tile, idx, fid) {
    tile.addEventListener('mousedown', (e) => {
      if (mode !== 'validate') return;
      // Do not start selection if the user interacted with UI controls on the tile
      const ctl = e.target && (e.target.closest('.ft-edit') || e.target.closest('.ft-view') || e.target.closest('.face-badge'));
      if (ctl) {
        // Prevent selection toggle
        return;
      }
      isDraggingSelect = true;
      const rangeMode = !!e.shiftKey && lastSelectedIndex >= 0;
      if (rangeMode) {
        // Range selection always adds
        const start = Math.min(lastSelectedIndex, idx);
        const end = Math.max(lastSelectedIndex, idx);
        for (let i = start; i <= end; i++) {
          const f = faces[i]; const id = f && (f.faceId || f.id);
          if (!id) continue;
          setSelected(id, true);
          const t = nodes[i]; if (t) updateTileSelection(t, true);
        }
        dragIntentAdd = true;
      } else {
        // start drag intent based on initial state on the starting tile (single click or drag)
        dragIntentAdd = !(view.__selected && view.__selected.has(fid));
        setSelected(fid, dragIntentAdd);
        updateTileSelection(tile, !!(view.__selected && view.__selected.has(fid)));
        lastSelectedIndex = idx;
      }
      e.preventDefault();
      // Keep header actions in sync
      if (view && typeof view.__updateActions === 'function') view.__updateActions();
      // Attach a one-off mouseup to end this drag cycle
      const endDrag = () => {
        isDraggingSelect = false;
        dragIntentAdd = null;
        if (view && typeof view.__updateActions === 'function') view.__updateActions();
      };
      document.addEventListener('mouseup', endDrag, { once: true });
    });
    tile.addEventListener('mouseenter', () => {
      if (mode !== 'validate' || !isDraggingSelect) return;
      // Apply current drag intent (add or remove)
      const add = !!dragIntentAdd;
      setSelected(fid, add);
      updateTileSelection(tile, !!(view.__selected && view.__selected.has(fid)));
      // keep the button count live while dragging
      if (view && typeof view.__updateActions === 'function') view.__updateActions();
    });
  }

  function updateTileSelection(tile, on) {
    tile.classList.toggle('selected', !!on);
    tile.style.outline = on ? '3px solid #2563eb' : '';
    tile.style.outlineOffset = on ? '0' : '';
  }

  // Removed the global once mouseup listener in favor of per-drag mouseup attached on mousedown

  // Ensure timestamp tooltip formatting
  function tsLabel(ts) {
    try { const d = new Date(ts); return d.toLocaleString(); } catch { return String(ts); }
  }

  // Tooltip helper for faces
  let faceTooltip = document.getElementById('person-faces-tooltip');
  if (!faceTooltip) {
    faceTooltip = document.createElement('div');
    faceTooltip.id = 'person-faces-tooltip';
    faceTooltip.className = 'mosaic-photo-tooltip';
    faceTooltip.style.position = 'fixed';
    faceTooltip.style.zIndex = '9999';
    faceTooltip.style.display = 'none';
    document.body.appendChild(faceTooltip);
  }
  function hideFaceTooltip() { 
    if (faceTooltip) {
      faceTooltip.classList.remove('show');
      faceTooltip.style.display = 'none'; 
    }
  }
  function showFaceTooltip(html, x, y) {
    if (!faceTooltip) return;
    faceTooltip.innerHTML = html;
    faceTooltip.style.display = 'block';
    // Force reflow to enable transition if needed, but here we just want it visible
    requestAnimationFrame(() => faceTooltip.classList.add('show'));
    
    const vw = window.innerWidth;
    const vh = window.innerHeight;
    const rect = faceTooltip.getBoundingClientRect();
    let left = x + 14;
    let top = y + 12;
    if (left + rect.width > vw) left = x - rect.width - 14;
    if (top + rect.height > vh) top = y - rect.height - 12;
    faceTooltip.style.left = Math.max(0, left) + 'px';
    faceTooltip.style.top = Math.max(0, top) + 'px';
  }

  for (const face of faces) {
    const tile = document.createElement('div');
    tile.className = 'face-tile';
    // Only show the magnifier button in validation mode
    tile.innerHTML = `
      <img class="face-img" alt="face" loading="lazy" decoding="async" />
      ${mode === 'validate' ? '<button type="button" class="ft-view" title="Open media in viewer" aria-label="View">🔍</button>' : ''}
      <button type="button" class="ft-edit" title="Edit face person" aria-label="Edit">✎</button>
    `;
    const img = tile.querySelector('img.face-img');
    if (img) {
      img.src = api.faceImageUrl(face.faceId || face.id);
      // img.title = tsLabel(face.timestamp); // tooltip with timestamp
      
      // Enhanced tooltip with miniature (delayed)
      let hoverTimer = null;
      
      const showTip = (e) => {
        const hint = (mode === 'validate') 
          ? 'Click to select<br>Shift-click range<br>Drag to multi-select' 
          : 'Click to open in Viewer';
        const baseHtml = `
          <div class="title">${tsLabel(face.timestamp)}</div>
          <div class="subtitle" style="font-size:0.85em;opacity:0.8;margin-top:4px">${hint}</div>
        `;
        
        // Capture coordinates for the delayed show
        const startX = e.clientX;
        const startY = e.clientY;

        hoverTimer = setTimeout(async () => {
             showFaceTooltip(baseHtml, startX, startY);
             
             // Fetch key if missing
             if (!face.__mediaAccessKey) {
                 try {
                     const oid = face.originalId || (face.original && face.original.id);
                     if (oid) {
                         const st = await api.getState(oid);
                         if (st && (st.mediaAccessKey || st.accessKey)) {
                             face.__mediaAccessKey = st.mediaAccessKey || st.accessKey;
                         }
                     }
                 } catch {}
             }
             
             if (face.__mediaAccessKey) {
                 const url = api.mediaMiniatureUrl(face.__mediaAccessKey);
                 // Preload image to avoid layout jump
                 const preload = new Image();
                 preload.onload = () => {
                     // Only update if tooltip is still shown and we are still hovering this face (timer not cleared/reset)
                     if (faceTooltip && faceTooltip.style.display !== 'none') {
                         const fullHtml = `
                           <div class="title">${tsLabel(face.timestamp)}</div>
                           <div style="margin:6px 0"><img src="${url}" style="max-width:250px;max-height:250px;border-radius:4px;display:block;object-fit:contain;background:#000"></div>
                           <div class="subtitle" style="font-size:0.85em;opacity:0.8">${hint}</div>
                         `;
                         showFaceTooltip(fullHtml, startX, startY);
                     }
                 };
                 preload.src = url;
             }
        }, 600); // 600ms delay
      };

      img.addEventListener('mouseenter', showTip);
      img.addEventListener('mouseleave', () => {
          if (hoverTimer) { clearTimeout(hoverTimer); hoverTimer = null; }
          hideFaceTooltip();
      });
      img.addEventListener('mousemove', (e) => {
         // If tooltip is already visible, just move it
         if (faceTooltip && faceTooltip.style.display !== 'none') {
            showFaceTooltip(faceTooltip.innerHTML, e.clientX, e.clientY);
         } else {
            // If waiting for delay, reset timer on significant move, or keep it? 
            // Standard UX: reset timer if mouse keeps moving to avoid popping up while traversing
            if (hoverTimer) { clearTimeout(hoverTimer); hoverTimer = null; }
            showTip(e);
         }
      });

      if (mode === 'validate') {
        // Avoid native image drag which can interfere with selection dragging
        try { img.draggable = false; } catch {}
        // Improve UX when drag-selecting
        try { tile.style.userSelect = 'none'; } catch {}
      }
    }

    // Wire viewer button (present only in validation mode)
    const viewBtn = tile.querySelector('.ft-view');
    if (viewBtn) {
      // Prevent selection from starting when pressing the button
      viewBtn.addEventListener('mousedown', (e) => { e.preventDefault(); e.stopPropagation(); });
      viewBtn.addEventListener('click', (e) => { e.stopPropagation(); e.preventDefault(); goToViewerForFace(face); });
    }

    // Make tile navigable to Viewer on click/keyboard
    try {
      if (mode === 'validate') {
        // Selection & range selection
        const fid = face.faceId || face.id;
        tile.style.cursor = 'crosshair';
        // tile.title = 'Click to select; Shift-click for range; Drag to multi-select'; // Disabled to use custom tooltip
        if (!tile.hasAttribute('tabindex')) tile.tabIndex = 0;
        // In validation mode, selection is handled on mousedown/drag; prevent click default to avoid double toggling
        tile.addEventListener('click', (e) => { e.preventDefault(); e.stopPropagation(); });
        tile.addEventListener('keydown', (e) => {
          if (e.key === 'Enter' || e.key === ' ') {
            // Keyboard toggle behaves like a single click: toggle this tile
            e.preventDefault();
            const turnOn = !(view.__selected && view.__selected.has(fid));
            setSelected(fid, turnOn);
            updateTileSelection(tile, !!(view.__selected && view.__selected.has(fid)));
            lastSelectedIndex = myIndex;
            if (view && typeof view.__updateActions === 'function') view.__updateActions();
          }
        });
        // Capture the index for this tile at creation time
        const myIndex = nodes.length;
        applyDraggingHandlers(tile, myIndex, fid);
        // Apply current selection state if any
        updateTileSelection(tile, !!(view.__selected && view.__selected.has(fid)));
      } else {
        tile.style.cursor = 'pointer';
        // tile.title = 'Open photo in Viewer'; // Disabled to use custom tooltip
        if (!tile.hasAttribute('tabindex')) tile.tabIndex = 0;
        tile.addEventListener('click', (e) => { e.preventDefault(); goToViewerForFace(face); });
        tile.addEventListener('keydown', (e) => {
          if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); goToViewerForFace(face); }
        });
      }
    } catch {}

    // Inferred-only badge when applicable
    const isIdentified = !!face.identifiedPersonId;
    // Use robust equality to handle number/string id variations
    const isInferredForThis = !isIdentified && (String(face.inferredIdentifiedPersonId ?? '') === String(person.id ?? ''));
    if (isInferredForThis) {
      const badge = document.createElement('button');
      badge.type = 'button';
      badge.className = 'face-badge inferred';
      badge.textContent = 'inferred';
      badge.title = 'Click to confirm identification';
      // Avoid triggering selection by holding mouse on the badge
      badge.addEventListener('mousedown', (e) => { e.preventDefault(); e.stopPropagation(); });
      badge.addEventListener('click', async (e) => {
        e.preventDefault(); e.stopPropagation();
        try {
          await api.setFacePerson(face.faceId || face.id, person.id);
          // Update local caches
          const fid = face.faceId || face.id;
          // Lookup in the current view cache (avoid calling helper out of scope)
          const inCache = (view && Array.isArray(view.__allFaces))
            ? (view.__allFaces.find(f => (f.faceId || f.id) === fid) || null)
            : null;
          if (inCache) {
            inCache.identifiedPersonId = person.id;
            inCache.inferredIdentifiedPersonId = null;
          }
          face.identifiedPersonId = person.id;
          face.inferredIdentifiedPersonId = null;
          try { pushRecentPersonId(person.id); } catch {}
          showSuccess('Face identification confirmed');
          // Remove from current selection if any and refresh locally (no reload)
          try {
            if (view && view.__selected && typeof view.__selected.delete === 'function') {
              view.__selected.delete(fid);
            }
          } catch {}
          // Safely refresh the grid and header actions using view-exposed hooks
          try {
            if (view && typeof view.__refreshGrid === 'function') view.__refreshGrid();
            if (view && typeof view.__updateActions === 'function') view.__updateActions();
          } catch {}
        } catch (err) {
          const details = err?.response?.data?.message || err?.response?.data?.error || err?.message || '';
          showError(`Failed to confirm face${details ? ': ' + details : ''}`);
        }
      });
      tile.appendChild(badge);
    }

    // Edit button opens existing modal
    const editBtn = tile.querySelector('.ft-edit');
    if (editBtn) editBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      openFaceEditModal(face, { onChanged: (updated) => {
        // Apply local mutations to the cache according to new identification
        try {
          const fid = (updated.faceId || updated.id || face.faceId || face.id);
          const cacheFace = (view && Array.isArray(view.__allFaces))
            ? (view.__allFaces.find(f => (f.faceId || f.id) === fid) || null)
            : null;
          const updatedIdent = updated.identifiedPersonId ?? cacheFace?.identifiedPersonId ?? face.identifiedPersonId ?? null;
          const updatedInferred = updated.inferredIdentifiedPersonId ?? cacheFace?.inferredIdentifiedPersonId ?? face.inferredIdentifiedPersonId ?? null;
          if (cacheFace) {
            cacheFace.identifiedPersonId = updatedIdent;
            cacheFace.inferredIdentifiedPersonId = updatedInferred;
          }
          // Decide visibility against the current person according to rules
          const nowIdentToThis = !!updatedIdent && idEq(updatedIdent, person.id);
          const nowToValidateForThis = (!updatedIdent) && idEq(updatedInferred, person.id);
          if (!nowIdentToThis && !nowToValidateForThis) {
            // Edited away from this person (either identified to someone else or not relevant here) → remove from both lists
            removeFaceFromAllFacesById(fid);
          }
          // Remove from selection if present (avoid cross-scope helper)
          try {
            if (view && view.__selected && typeof view.__selected.delete === 'function') {
              view.__selected.delete(fid);
            }
          } catch {}
        } catch {}
        // Refresh locally using view-exposed hooks
        try {
          if (view && typeof view.__refreshGrid === 'function') view.__refreshGrid();
          if (view && typeof view.__updateActions === 'function') view.__updateActions();
        } catch {}
      }, onDeleted: (deletedFaceId) => {
        try { removeFaceFromAllFacesById(deletedFaceId || (face.faceId || face.id)); } catch {}
        try {
          const rid = deletedFaceId || (face.faceId || face.id);
          if (view && view.__selected && typeof view.__selected.delete === 'function') {
            view.__selected.delete(rid);
          }
        } catch {}
        try {
          if (view && typeof view.__refreshGrid === 'function') view.__refreshGrid();
          if (view && typeof view.__updateActions === 'function') view.__updateActions();
        } catch {}
      }});
    });
    if (editBtn) {
      // Also suppress mousedown to avoid starting a drag-select when clicking the edit button
      editBtn.addEventListener('mousedown', (e) => { e.preventDefault(); e.stopPropagation(); });
    }

    nodes.push(tile);
  }
  try { grid.replaceChildren(...nodes); } catch { grid.innerHTML = ''; nodes.forEach(n => grid.appendChild(n)); }
  // Sync header actions (button visibility and counts)
  if (view && typeof view.__updateActions === 'function') view.__updateActions();
}

function openPersonCreateModal() {
  if (document.querySelector('.modal-overlay')) return;
  const overlay = document.createElement('div'); overlay.className = 'modal-overlay';
  overlay.innerHTML = `
    <div class="modal" role="dialog" aria-modal="true" tabindex="-1">
      <header>
        <div>Create person</div>
        <button class="close" title="Close" style="background:none;border:none;font-size:18px;cursor:pointer">✕</button>
      </header>
      <div class="content">
        <div class="row">
          <div>
            <label>First name</label>
            <input type="text" id="pc-first" value="">
            <label style="margin-top:8px">Last name</label>
            <input type="text" id="pc-last" value="">
            <label style="margin-top:8px">Birthdate</label>
            <input type="date" id="pc-birth" value="">
            <label style="margin-top:8px">Email</label>
            <input type="email" id="pc-email" value="">
            <label style="margin-top:8px">Description</label>
            <input type="text" id="pc-desc" value="">
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
  setTimeout(()=>{ (modal.querySelector('#pc-first')||modal).focus(); }, 0);

  overlay.querySelector('button.save')?.addEventListener('click', async () => {
    const firstName = modal.querySelector('#pc-first').value.trim();
    const lastName  = modal.querySelector('#pc-last').value.trim();
    const birth     = modal.querySelector('#pc-birth').value; // yyyy-mm-dd or ''
    const email     = modal.querySelector('#pc-email').value.trim();
    const desc      = modal.querySelector('#pc-desc').value.trim();
    if (!firstName || !lastName) { showWarning('First name and Last name are required'); return; }
    const body = { firstName, lastName };
    if (!birth) body.birthDate = null; else body.birthDate = `${birth}T00:00:00Z`;
    if (email) body.email = email;
    if (desc) body.description = desc;
    try {
      await api.createPerson(body);
      close();
      // Invalidate Viewer persons cache so newly created person appears in the Viewer face edit modal without full page reload
      try { personsCache = null; } catch {}
      await loadPersons();
    } catch (e) {
      showError('Failed to create person');
    }
  });
}

function refreshPersonTile(updated) {
  const li = document.querySelector(`#persons-list li[data-person-id="${updated.id}"]`);
  if (!li) return;
  const birthStr = updated.birthDate ? new Date(updated.birthDate).toLocaleDateString() : '';
  const desc = updated.description || '';
  li.querySelector('h4').textContent = `${updated.firstName} ${updated.lastName}`;
  const meta = li.querySelector('div[style*="font-size:12px"]');
  if (meta) meta.textContent = `id: ${updated.id}${birthStr ? ' • birth: '+birthStr : ''}${desc ? ' • '+desc : ''}`;
  const thumb = li.querySelector('.person-thumb');
  if (thumb) {
    thumb.innerHTML = updated.chosenFaceId ? 'Loading…' : 'No image';
    if (updated.chosenFaceId) {
      const img = new Image();
      img.src = api.faceImageUrl(updated.chosenFaceId);
      img.alt = `${updated.firstName} ${updated.lastName}`;
      img.loading = 'lazy';
      img.decoding = 'async';
      img.style.width = '100%'; img.style.height = '100%'; img.style.objectFit = 'cover'; img.style.display = 'block';
      thumb.innerHTML = ''; thumb.style.background = 'transparent'; thumb.appendChild(img);
    }
  }
}

function openPersonEditModal(person) {
  if (!person) return;
  if (document.querySelector('.modal-overlay')) return;
  const overlay = document.createElement('div'); overlay.className = 'modal-overlay';
  const birthVal = person.birthDate ? new Date(person.birthDate) : null;
  const toDateInput = (d) => { try { if (!d) return ''; const yyyy=d.getFullYear(); const mm=String(d.getMonth()+1).padStart(2,'0'); const dd=String(d.getDate()).padStart(2,'0'); return `${yyyy}-${mm}-${dd}`; } catch { return ''; } };
  const faceUrl = person.chosenFaceId ? api.faceImageUrl(person.chosenFaceId) : null;
  overlay.innerHTML = `
    <div class="modal" role="dialog" aria-modal="true" tabindex="-1" style="width:600px;max-width:95vw">
      <header>
        <div>Edit person</div>
        <button class="close" title="Close" style="background:none;border:none;font-size:18px;cursor:pointer">✕</button>
      </header>
      <div class="content">
        <div class="row" style="display:flex;gap:16px">
          <div style="flex:1">
            <label>First name</label>
            <input type="text" id="pe-first" value="${(person.firstName||'').replace(/&/g,'&amp;').replace(/"/g,'&quot;')}">
            <label style="margin-top:8px">Last name</label>
            <input type="text" id="pe-last" value="${(person.lastName||'').replace(/&/g,'&amp;').replace(/"/g,'&quot;')}">
            <label style="margin-top:8px">Birthdate</label>
            <input type="date" id="pe-birth" value="${toDateInput(birthVal)}">
            <label style="margin-top:8px">Email</label>
            <input type="email" id="pe-email" value="${(person.email||'').replace(/&/g,'&amp;').replace(/"/g,'&quot;')}">
            <label style="margin-top:8px">Description</label>
            <input type="text" id="pe-desc" value="${(person.description||'').replace(/&/g,'&amp;').replace(/"/g,'&quot;')}">
            <input type="hidden" id="pe-chosen" value="${(person.chosenFaceId||'').replace(/&/g,'&amp;').replace(/"/g,'&quot;')}">
          </div>
          <div style="width:200px;display:flex;flex-direction:column;align-items:center">
            ${faceUrl ? `<img src="${faceUrl}" style="width:100%;border-radius:4px;object-fit:contain;max-height:300px;background:#eee">` : '<div style="width:100%;height:150px;background:#f3f4f6;display:flex;align-items:center;justify-content:center;color:#9ca3af;border-radius:4px">No face</div>'}
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
  setTimeout(()=>{ (modal.querySelector('#pe-first')||modal).focus(); }, 0);

  overlay.querySelector('button.save')?.addEventListener('click', async () => {
    const firstName = modal.querySelector('#pe-first').value.trim();
    const lastName  = modal.querySelector('#pe-last').value.trim();
    const birth     = modal.querySelector('#pe-birth').value;
    const email     = modal.querySelector('#pe-email').value.trim();
    const desc      = modal.querySelector('#pe-desc').value.trim();
    const chosen    = modal.querySelector('#pe-chosen').value.trim();
    if (!firstName || !lastName) { showWarning('First name and Last name are required'); return; }
    const body = { firstName, lastName };
    body.birthDate = birth ? `${birth}T00:00:00Z` : null;
    if (email) body.email = email; else body.email = null;
    if (desc) body.description = desc; else body.description = null;
    if (chosen) body.chosenFaceId = chosen; else body.chosenFaceId = null;
    try {
      await api.updatePerson(person.id, body);
      close();
      // Invalidate Viewer persons cache so edits are reflected in Viewer face edit modal (typeahead)
      try { personsCache = null; } catch {}
      // Refetch person list to get canonical data and refresh tile
      const persons = await api.listPersons();
      const updated = persons.find(x => x.id === person.id) || { ...person, ...body };
      refreshPersonTile(updated);
    } catch (e) { showError('Failed to update person'); }
  });
}

function initPersonsTab() {
  const refreshBtn = document.getElementById('refresh-persons');
  if (refreshBtn && !refreshBtn.__wired) { refreshBtn.addEventListener('click', loadPersons); refreshBtn.__wired = true; }
  const createBtn = document.getElementById('create-person');
  if (createBtn && !createBtn.__wired) { createBtn.addEventListener('click', () => openPersonCreateModal()); createBtn.__wired = true; }
  const filterInput = document.getElementById('persons-filter');
  const resetBtn = document.getElementById('reset-persons-filter');
  // Restore filter from session storage (persist across reloads in the session)
  const ssKey = 'personsTab.filter';
  try {
    const saved = sessionStorage.getItem(ssKey);
    if (typeof saved === 'string' && saved.length > 0) {
      personsFilter = saved;
      if (filterInput) filterInput.value = saved;
    }
  } catch {}

  if (filterInput && !filterInput.__wired) {
    filterInput.addEventListener('input', (e) => {
      personsFilter = String(e.target.value || '').trim();
      try { sessionStorage.setItem(ssKey, personsFilter); } catch {}
      renderPersonsList();
    });
    filterInput.__wired = true;
  }
  if (resetBtn && !resetBtn.__wired) {
    resetBtn.addEventListener('click', () => {
      personsFilter = '';
      if (filterInput) filterInput.value = '';
      try { sessionStorage.removeItem(ssKey); } catch {}
      renderPersonsList();
      try { if (filterInput) filterInput.focus(); } catch {}
    });
    resetBtn.__wired = true;
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
  const baseDirVal = store.baseDirectory || '';
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
            <label style="margin-top:8px">Base directory</label>
            <input type="text" id="st-basedir" placeholder="/path/to/base/directory" value="${(baseDirVal||'').replace(/&/g,'&amp;').replace(/"/g,'&quot;')}">
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
    const baseDirectory = modal.querySelector('#st-basedir').value;
    const includeMask = modal.querySelector('#st-include').value;
    const ignoreMask = modal.querySelector('#st-ignore').value;
    const body = { name, baseDirectory, includeMask, ignoreMask };
    try {
      await api.updateStore(store.id, body);
      close();
      // refetch this store from list to get latest values
      const stores = await api.listStores();
      const updated = stores.find(x => x.id === store.id) || { ...store, name, baseDirectory, includeMask, ignoreMask };
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
    const processed = typeof st?.processedCount === 'number' ? st.processedCount : 0;
    const checked = typeof st?.checkedCount === 'number' ? st.checkedCount : 0;
    const updated = st?.lastUpdated ? new Date(st.lastUpdated).toLocaleString() : 'never';
    
    if (running && st?.startedAt) {
      const duration = formatDuration(st.startedAt);
      statusEl.textContent = `Running for ${duration}… processed ${processed} / ${checked} item(s). Last update: ${updated}`;
    } else if (running) {
      statusEl.textContent = `Running… processed ${processed} / ${checked} item(s). Last update: ${updated}`;
    } else {
      statusEl.textContent = `Idle. Last run update: ${updated}. Total processed: ${processed} / ${checked}`;
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
  const cbFast = document.getElementById('sync-fast');
  const inputDays = document.getElementById('sync-days');

  // Restore persisted values
  try {
    const savedEnabled = localStorage.getItem('settings.syncFastEnabled');
    if (cbFast && savedEnabled != null) cbFast.checked = savedEnabled === 'true';
    const savedDays = localStorage.getItem('settings.syncDays');
    if (inputDays && savedDays) inputDays.value = savedDays;
  } catch {}

  const updateButtonLabel = () => {
    if (!btn) return;
    if (cbFast && cbFast.checked) btn.textContent = '▷ Synchronize (quick)';
    else btn.textContent = '▷ Synchronize (full)';
  };
  updateButtonLabel();

  if (cbFast && !cbFast.__wired) {
    cbFast.addEventListener('change', () => {
      try { localStorage.setItem('settings.syncFastEnabled', cbFast.checked ? 'true' : 'false'); } catch {}
      updateButtonLabel();
    });
    cbFast.__wired = true;
  }
  if (inputDays && !inputDays.__wired) {
    inputDays.addEventListener('change', () => {
      const v = inputDays.value.trim();
      if (v) { try { localStorage.setItem('settings.syncDays', v); } catch {} }
    });
    inputDays.__wired = true;
  }

  if (btn && !btn.__wired) {
    btn.addEventListener('click', async () => {
      const statusEl = document.getElementById('sync-status');
      statusEl.textContent = 'Starting synchronization…';
      btn.disabled = true;
      let daysParam = undefined;
      try {
        if (cbFast && cbFast.checked) {
          const raw = (inputDays?.value || '').trim();
          const n = parseInt(raw, 10);
          if (!Number.isFinite(n) || n <= 0) {
            showWarning('Please provide a valid number of days (> 0).');
            btn.disabled = false;
            return;
          }
          daysParam = n;
        }
        await api.synchronizeStart(daysParam);
        await refreshSyncStatus();
      } catch(e) {
        statusEl.textContent = 'Failed to start synchronization.';
        btn.disabled = false;
      }
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
  // Inject Add Face button near the edit button
  if (cont && !document.getElementById('img-add-btn')) {
    const addBtn = document.createElement('button');
    addBtn.id = 'img-add-btn';
    addBtn.className = 'img-add-btn';
    addBtn.type = 'button';
    addBtn.title = 'Add face (drag on photo)';
    addBtn.setAttribute('aria-label', 'Add face');
    addBtn.textContent = '+ Add face';
    addBtn.addEventListener('click', (e) => { e.preventDefault(); e.stopPropagation(); try { toggleAddFaceMode(true); } catch (err) { console.warn('toggleAddFaceMode failed', err); } });
    cont.appendChild(addBtn);
  }
  initEventsTab();
  initPersonsTab();
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
