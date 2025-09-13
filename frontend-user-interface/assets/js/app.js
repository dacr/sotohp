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
  async listEvents() { return await this.#fetchNdjson('/api/events'); }
  async createEvent(name) { const res = await this.http.post('/api/event', { name }); return res.data; }
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
  if (name === 'world') ensureMap();
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
  const img = $('#main-image');
  img.src = api.mediaNormalizedUrl(media.accessKey) + `?t=${Date.now()}`; // cache bust
  const date = media.shootDateTime || media.original?.cameraShootDateTime || '-';
  const dateStr = date ? new Date(date).toLocaleString() : '-';
  const eventName = (media.events && media.events.length > 0) ? media.events[0].name : '-';
  $('#info-date').textContent = dateStr;
  $('#info-event').textContent = eventName;
  $('#info-starred').textContent = media.starred ? '★ Yes' : 'No';
  const hasLoc = !!(media.location || media.userDefinedLocation || media.deductedLocation);
  $('#info-hasloc').textContent = hasLoc ? 'Yes' : 'No';
  $('#info-keywords').textContent = (media.keywords && media.keywords.length) ? media.keywords.join(', ') : '-';
  // Update fullscreen overlay content
  const ov = document.getElementById('fs-overlay');
  if (ov) {
    const star = media.starred ? '★ ' : '';
    const loc = hasLoc ? ' 📍' : '';
    ov.innerHTML = `<div class="title">${star}${eventName}${loc}</div><div class="sub">${dateStr}</div>`;
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
  // Toggle fullscreen on double click on the image/container
  (document.querySelector('.image-container'))?.addEventListener('dblclick', () => {
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
}

// Leaflet map
let map = null; let cluster = null; let mapLoaded = false;
function ensureMap() {
  if (mapLoaded) return;
  mapLoaded = true;
  map = L.map('map').setView([20, 0], 2);
  L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', { attribution: '&copy; OpenStreetMap' }).addTo(map);
  cluster = L.markerClusterGroup();
  map.addLayer(cluster);
  $('#map-status').textContent = 'Loading medias with location…';
  let count = 0;
  api.mediasWithLocations(m => {
    const loc = m.location || m.userDefinedLocation || m.deductedLocation; if (!loc) return;
    const marker = L.marker([loc.latitude, loc.longitude]);
    const thumbUrl = api.mediaNormalizedUrl(m.accessKey);
    const date = m.shootDateTime || m.original?.cameraShootDateTime || '';
    const eventName = (m.events && m.events.length > 0) ? m.events[0].name : '';
    const starred = m.starred ? '★' : '';
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
    cluster.addLayer(marker);
    count += 1; if (count % 200 === 0) $('#map-status').textContent = `Loaded ${count}…`;
  }).then(() => { $('#map-status').textContent = 'Done'; }).catch(() => { $('#map-status').textContent = 'Failed to load medias'; });
}

// Events
async function loadEvents() {
  const list = $('#events-list'); list.innerHTML = '';
  try {
    const events = await api.listEvents();
    for (const ev of events) {
      const li = document.createElement('li');
      li.innerHTML = `<h4>${ev.name}</h4><div style="font-size:12px;color:#555">${ev.id}</div>`;
      list.appendChild(li);
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
  // Initial media
  loadMedia('random');
}

document.addEventListener('DOMContentLoaded', init);
