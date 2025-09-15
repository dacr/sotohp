// Compiled JavaScript (manually authored) corresponding to TypeScript sources.
// Uses axios (via CDN) and Leaflet (via CDN). ES module for clarity.

class ApiClient {
  constructor(baseURL = '') { this.http = axios.create({ baseURL }); }
  async getMedia(select, referenceMediaAccessKey) {
    const res = await this.http.get('/api/media', { params: { select, referenceMediaAccessKey } });
    return res.data;
  }
  async getMediaByKey(mediaAccessKey) {
    const res = await this.http.get(`/api/media/${encodeURIComponent(mediaAccessKey)}`);
    return res.data;
  }
  mediaNormalizedUrl(mediaAccessKey) { return `/api/media/${encodeURIComponent(mediaAccessKey)}/normalized`; }
  mediaMiniatureUrl(mediaAccessKey) { return `/api/media/${encodeURIComponent(mediaAccessKey)}/miniature`; }
  async listEvents() { return await this.#fetchNdjson('/api/events'); }
  async getState(originalId) { const res = await this.http.get(`/api/state/${encodeURIComponent(originalId)}`); return res.data; }
  async createEvent(name) { const res = await this.http.post('/api/event', { name }); return res.data; }
  async updateEvent(eventId, body) { await this.http.put(`/api/event/${encodeURIComponent(eventId)}`, body); }
  async listOwners() { return await this.#fetchNdjson('/api/owners'); }
  async listStores() { return await this.#fetchNdjson('/api/stores'); }
  async synchronize() { await this.http.get('/api/admin/synchronize'); }
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
  if (name === 'events') loadEvents();
  if (name === 'owners') loadOwners();
  if (name === 'stores') loadStores();
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
  $('#info-starred').textContent = media.starred ? '⭐ Yes' : '☆ No';
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
  $('#info-keywords').textContent = (media.keywords && media.keywords.length) ? media.keywords.join(', ') : '-';
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
    const btn = $('#btn-play'); if (btn) btn.textContent = '▶️';
  }
  function scheduleNextTick() {
    if (!slideshowPlaying) return;
    const sel = $('#slideshow-delay').value || '20-next';
    const [secsStr, mode] = sel.split('-');
    const delay = (parseInt(secsStr, 10) || 20) * 1000;
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
    slideshowPlaying = true; btn.textContent = '⏸️';
    // Start waiting according to current selection, then advance
    scheduleNextTick();
  });

  // If the user changes the delay/mode while playing, apply immediately
  $('#slideshow-delay').addEventListener('change', () => {
    if (!slideshowPlaying) return;
    if (slideshowTimer) { clearTimeout(slideshowTimer); slideshowTimer = null; }
    scheduleNextTick();
  });

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

function openEventEditModal(ev) {
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

  function close() { overlay.remove(); }
  overlay.querySelector('button.close')?.addEventListener('click', close);
  overlay.querySelector('button.cancel')?.addEventListener('click', close);
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
    if (!val) return; if (!keywords.includes(val)) keywords.push(val); input.value=''; renderChips();
  }
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' || e.key === ',') { e.preventDefault(); addKeywordFromInput(); }
    else if (e.key === 'Backspace' && !input.value && keywords.length > 0) { keywords.pop(); renderChips(); }
  });
  input.addEventListener('blur', () => { addKeywordFromInput(); });
  renderChips();

  // Map setup
  let currentLoc = ev.location ? { ...ev.location } : null;
  setTimeout(() => {
    try {
      const mapDiv = overlay.querySelector('#event-edit-map');
      const m = L.map(mapDiv).setView(currentLoc ? [currentLoc.latitude, currentLoc.longitude] : [20, 0], currentLoc ? 13 : 2);
      L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', { attribution: '&copy; OpenStreetMap' }).addTo(m);
      let marker = null;
      function setMarker(latlng) {
        if (!marker) { marker = L.marker(latlng, { draggable: true }).addTo(m); marker.on('dragend', () => { const ll = marker.getLatLng(); currentLoc = { latitude: ll.lat, longitude: ll.lng, altitude: currentLoc?.altitude }; }); }
        else { marker.setLatLng(latlng); }
        currentLoc = { latitude: latlng.lat, longitude: latlng.lng, altitude: currentLoc?.altitude };
      }
      if (currentLoc) setMarker({ lat: currentLoc.latitude, lng: currentLoc.longitude });
      m.on('click', (e) => setMarker(e.latlng));
      setTimeout(() => { try { m.invalidateSize(true); } catch {} }, 0);
    } catch {}
  }, 0);

  // Save handler
  overlay.querySelector('button.save')?.addEventListener('click', async () => {
    const name = overlay.querySelector('#ev-name').value.trim();
    if (!name) { alert('Name is required'); return; }
    const description = overlay.querySelector('#ev-desc').value.trim();
    const tsLocal = overlay.querySelector('#ev-ts').value;
    const timestamp = fromLocalInputValue(tsLocal);
    const body = { name };
    if (description) body.description = description;
    if (timestamp) body.timestamp = timestamp;
    if (currentLoc && typeof currentLoc.latitude === 'number' && typeof currentLoc.longitude === 'number') body.location = currentLoc;
    if (keywords && keywords.length > 0) body.keywords = keywords;
    try {
      await api.updateEvent(ev.id, body);
      close();
      await loadEvents();
    } catch {
      alert('Failed to save event');
    }
  });
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
          try { const media = await api.getMediaByKey(st.mediaAccessKey); setActiveTab('viewer'); showMedia(media); } catch {}
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
      const tsStr = ev.timestamp ? new Date(ev.timestamp).toLocaleString() : '';
      li.innerHTML = `
        <div class="ev-thumb" style="width:100%;height:160px;border-radius:6px;background:#f3f4f6;display:flex;align-items:center;justify-content:center;overflow:hidden;margin-bottom:6px;color:#9ca3af;font-size:12px;">No preview</div>
        <h4 style="margin:0 0 4px 0;">${ev.name || '(no name)'}</h4>
        <div style="font-size:12px;color:#555">${tsStr}</div>
        <button class="ev-edit-btn" title="Edit">✎ Edit</button>
      `;
      list.appendChild(li);
      const editBtn = li.querySelector('.ev-edit-btn');
      editBtn?.addEventListener('click', (e) => { e.stopPropagation(); openEventEditModal(ev); });

      // Clicking before image is loaded should still navigate when possible
      if (ev.originalId) {
        li.style.cursor = 'pointer';
        li.onclick = async () => {
          try {
            let p = stateCache.get(ev.originalId);
            if (!p) { p = api.getState(ev.originalId).catch(err => { stateCache.delete(ev.originalId); throw err; }); stateCache.set(ev.originalId, p); }
            const st = await p; if (!st || !st.mediaAccessKey) return;
            const media = await api.getMediaByKey(st.mediaAccessKey); setActiveTab('viewer'); showMedia(media);
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

function initEventsTab() {
  $('#refresh-events').addEventListener('click', loadEvents);
  const form = document.getElementById('event-create-form');
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    const name = document.getElementById('event-name').value.trim();
    if (!name) return;
    try { await api.createEvent(name); document.getElementById('event-name').value=''; await loadEvents(); }
    catch { alert('Failed to create event'); }
  });
}

// Owners
async function loadOwners() {
  const list = $('#owners-list'); list.innerHTML = '';
  try {
    const owners = await api.listOwners();
    const ownerSelect = document.getElementById('store-owner');
    ownerSelect.innerHTML = owners.map(o => `<option value="${o.id}">${o.firstName} ${o.lastName}</option>`).join('');
    for (const o of owners) {
      const li = document.createElement('li');
      li.innerHTML = `<h4>${o.firstName} ${o.lastName}</h4><div style="font-size:12px;color:#555">${o.id}</div>`;
      list.appendChild(li);
    }
  } catch (e) { list.innerHTML = '<li>Failed to load owners</li>'; }
}

// Stores
async function loadStores() {
  const list = $('#stores-list'); list.innerHTML = '';
  try {
    const stores = await api.listStores();
    for (const s of stores) {
      const li = document.createElement('li');
      li.innerHTML = `<h4>${s.baseDirectory}</h4><div style="font-size:12px;color:#555">id: ${s.id} • owner: ${s.ownerId}</div>`;
      list.appendChild(li);
    }
  } catch (e) { list.innerHTML = '<li>Failed to load stores</li>'; }
}

function initStoresTab() {
  $('#refresh-stores').addEventListener('click', loadStores);
  const form = document.getElementById('store-create-form');
  // Not available in current OpenAPI (no POST /api/store). Disable submit and show info.
  form.addEventListener('submit', (e) => { e.preventDefault(); alert('Store creation is not available in the API.'); });
}

// Settings
function initSettings() {
  $('#btn-sync').addEventListener('click', async () => {
    const status = $('#sync-status'); status.textContent = 'Synchronizing…';
    try { await api.synchronize(); status.textContent = 'Synchronization requested.'; }
    catch { status.textContent = 'Failed to synchronize.'; }
  });
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
  initEventsTab();
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
