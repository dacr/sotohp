// Simple SPA for SotoHP UI
// Routes: /ui/, /ui/owners, /ui/stores, /ui/events

const API_BASE = '';

function navigate(path) {
  history.pushState(null, '', path);
  render();
}

function onLinkClick(e) {
  const a = e.target.closest('a[data-link]');
  if (a) {
    e.preventDefault();
    navigate(a.getAttribute('href'));
  }
}

const typeNone = undefined; // hint to keep file as module if TS tooling is used

window.addEventListener('click', onLinkClick);
window.addEventListener('popstate', render);

function routePath() {
  const base = '/ui';
  let p = location.pathname;
  if (p.startsWith(base)) p = p.substring(base.length);
  if (p === '' || p === '/') return '/';
  return p;
}

function h(html) {
  return html.trim();
}

function setRoot(html) {
  const root = document.getElementById('app-root');
  root.innerHTML = html;
}

function ndjson(url) {
  return fetch(url).then(async (r) => {
    const txt = await r.text();
    return txt.split('\n').filter(Boolean).map((line) => {
      try { return JSON.parse(line); } catch { return null; }
    }).filter(Boolean);
  });
}

function escapeHtml(str) {
  return str?.toString().replace(/[&<>"']/g, (c) => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;','\'':'&#39;'}[c])) ?? '';
}

// ---------------- Viewer (Random Image) ----------------
let randomTimer = null;
const SLIDESHOW_SPEEDS = [5000, 10000, 20000];
function getSlideshowMs() {
  const v = parseInt(localStorage.getItem('slideshowMs') || '5000', 10);
  return SLIDESHOW_SPEEDS.includes(v) ? v : 5000;
}
function setSlideshowMs(ms) {
  localStorage.setItem('slideshowMs', String(ms));
}
function prettyLabel(k) {
  // transform camelCase or snake_case to Title Case
  return k
    .replace(/[_-]+/g, ' ')
    .replace(/([a-z])([A-Z])/g, '$1 $2')
    .replace(/\s+/g, ' ')
    .replace(/^./, c => c.toUpperCase());
}
function renderMediaMeta(j, key) {
  if (!j || typeof j !== 'object') return '';
  const hideRe = /(\b|_)(id|key|hash|sha1?|md5|crc|uuid)(\b|_)/i;
  const entries = Object.entries(j)
    .filter(([k, v]) => !hideRe.test(k))
    .map(([k, v]) => {
      let val = v;
      if (Array.isArray(v)) {
        // Special-case for events: show first event name instead of [object Object]
        if ((k || '').toLowerCase() === 'events') {
          const first = v.find(x => x != null);
          if (first && typeof first === 'object') {
            val = first.name ?? first.title ?? first.id ?? '';
          } else {
            val = first != null ? String(first) : '';
          }
        } else {
          // Generic array handling: if array of objects, attempt to show a meaningful field from the first
          const first = v.find(x => x != null);
          if (first && typeof first === 'object') {
            val = first.name ?? first.title ?? first.label ?? first.id ?? '';
          } else {
            val = v.filter(x => x != null).join(', ');
          }
        }
      } else if (v && typeof v === 'object') {
        // Try to extract common nested fields like width/height/mimeType
        const w = v.width ?? v.w;
        const h = v.height ?? v.h;
        if (w && h) return [prettyLabel('dimensions'), `${w}×${h}`];
        const mt = v.mimeType ?? v.contentType;
        if (mt) return [prettyLabel('mime type'), String(mt)];
        return null; // skip unknown nested objects
      }
      return [prettyLabel(k), val];
    })
    .filter(Boolean)
    .slice(0, 12); // limit to keep concise
  const info = entries.map(([k, v]) => `<span class="badge" title="${escapeHtml(String(v))}">${escapeHtml(k)}: ${escapeHtml(String(v))}</span>`).join(' ');
  const keyHtml = key ? `<span class="badge" title="Media Access Key" style="display:none">${escapeHtml(key)}</span>` : '';
  return `${info} ${keyHtml}`.trim();
}
async function loadRandom() {
  try {
    const r = await fetch(API_BASE + '/api/media/random');
    const key = r.headers.get('Media-Access-Key');
    const blob = await r.blob();
    const url = URL.createObjectURL(blob);
    const img = document.getElementById('random-img');
    img.src = url;
    const meta = document.getElementById('random-meta');
    meta.textContent = '';
    if (key) {
      // Enrich with media metadata if available
      try {
        const jr = await fetch(API_BASE + '/api/media/' + encodeURIComponent(key));
        if (jr.ok) {
          const j = await jr.json();
          meta.innerHTML = renderMediaMeta(j, key);
          meta.title = 'Media information';
        } else {
          meta.title = `Media Access Key: ${key}`;
        }
      } catch {
        meta.title = `Media Access Key: ${key}`;
      }
    }
  } catch (e) {
    console.error('Random fetch failed', e);
  }
}

function viewerPage() {
  const currentMs = getSlideshowMs();
  setRoot(h(`
    <section class="card">
      <h2 class="section-title">Random Viewer</h2>
      <div class="form-row" style="align-items:center">
        <label for="speed-select" class="meta">Slideshow speed</label>
        <select id="speed-select" class="input" style="max-width:200px">
          <option value="5000" ${currentMs===5000?'selected':''}>Every 5 seconds</option>
          <option value="10000" ${currentMs===10000?'selected':''}>Every 10 seconds</option>
          <option value="20000" ${currentMs===20000?'selected':''}>Every 20 seconds</option>
        </select>
      </div>
      <div class="meta" id="random-meta"></div>
      <div class="image-wrap">
        <img id="random-img" alt="Random media"/>
      </div>
    </section>
  `));
  clearInterval(randomTimer);
  loadRandom();
  randomTimer = setInterval(loadRandom, currentMs);
  const speedSel = document.getElementById('speed-select');
  speedSel.addEventListener('change', () => {
    const ms = parseInt(speedSel.value, 10);
    setSlideshowMs(ms);
    clearInterval(randomTimer);
    randomTimer = setInterval(loadRandom, ms);
  });
}

// ---------------- Owners ----------------
async function ownersPage() {
  setRoot(h(`
    <section class="card">
      <h2 class="section-title">Owners</h2>
      <div id="owners-list">Loading…</div>
    </section>
  `));
  const el = document.getElementById('owners-list');
  try {
    const items = await ndjson(API_BASE + '/api/owner');
    el.innerHTML = renderOwners(items);
    wireOwnerForms();
  } catch (e) {
    el.textContent = 'Failed to load owners';
  }
}

function getId(obj) {
  return obj?.ownerId || obj?.id || obj?.storeId || obj?.eventId || obj?.mediaAccessKey || obj?.mediaId;
}

function renderOwners(items) {
  return `
    <table class="table">
      <thead>
        <tr><th>ID</th><th>First Name</th><th>Last Name</th><th>Birth Date</th><th>Actions</th></tr>
      </thead>
      <tbody>
        ${items.map(o => {
          const id = escapeHtml(getId(o));
          return `
            <tr>
              <td class="meta">${id}</td>
              <td><input class="input" name="firstName" value="${escapeHtml(o.firstName)}" data-id="${id}"></td>
              <td><input class="input" name="lastName" value="${escapeHtml(o.lastName)}" data-id="${id}"></td>
              <td><input class="input" name="birthDate" value="${escapeHtml(o.birthDate)}" data-id="${id}" placeholder="YYYY-MM-DD"></td>
              <td><button class="btn owner-save" data-id="${id}">Save</button></td>
            </tr>`;
        }).join('')}
      </tbody>
    </table>
  `;
}

function wireOwnerForms() {
  document.querySelectorAll('.owner-save').forEach(btn => {
    btn.addEventListener('click', async (e) => {
      const id = e.target.getAttribute('data-id');
      const rowInputs = Array.from(document.querySelectorAll(`input[data-id="${CSS.escape(id)}"]`));
      const body = {};
      rowInputs.forEach(inp => body[inp.name] = inp.value || null);
      try {
        const r = await fetch(API_BASE + '/api/owner/' + encodeURIComponent(id), {
          method: 'PUT', headers: {'Content-Type':'application/json'}, body: JSON.stringify(body)
        });
        if (!r.ok) throw new Error('Update failed');
        alert('Owner updated');
      } catch (e) {
        alert('Owner update failed');
      }
    });
  });
}

// ---------------- Stores ----------------
async function storesPage() {
  setRoot(h(`
    <section class="card">
      <h2 class="section-title">Stores</h2>
      <div id="stores-list">Loading…</div>
    </section>
  `));
  const el = document.getElementById('stores-list');
  try {
    const items = await ndjson(API_BASE + '/api/store');
    el.innerHTML = renderStores(items);
    wireStoreForms();
  } catch (e) {
    el.textContent = 'Failed to load stores';
  }
}

function renderStores(items) {
  return `
    <table class="table">
      <thead>
        <tr><th>ID</th><th>Include Mask</th><th>Ignore Mask</th><th>Actions</th></tr>
      </thead>
      <tbody>
        ${items.map(s => {
          const storeIdRaw = s.id || s.storeId; // ensure we use the store identifier, not ownerId
          const id = escapeHtml(storeIdRaw ?? '');
          return `
            <tr>
              <td class="meta">${id}</td>
              <td><input class="input" name="includeMask" value="${escapeHtml(s.includeMask)}" data-id="${id}" placeholder="glob, regex…"></td>
              <td><input class="input" name="ignoreMask" value="${escapeHtml(s.ignoreMask)}" data-id="${id}" placeholder="glob, regex…"></td>
              <td><button class="btn store-save" data-id="${id}">Save</button></td>
            </tr>`;
        }).join('')}
      </tbody>
    </table>
  `;
}

function wireStoreForms() {
  document.querySelectorAll('.store-save').forEach(btn => {
    btn.addEventListener('click', async (e) => {
      const id = e.target.getAttribute('data-id');
      const inputs = Array.from(document.querySelectorAll(`input[data-id="${CSS.escape(id)}"]`));
      const body = {};
      inputs.forEach(inp => body[inp.name] = inp.value || null);
      try {
        const r = await fetch(API_BASE + '/api/store/' + encodeURIComponent(id), {
          method: 'PUT', headers: {'Content-Type':'application/json'}, body: JSON.stringify(body)
        });
        if (!r.ok) throw new Error('Update failed');
        alert('Store updated');
      } catch (e) {
        alert('Store update failed');
      }
    });
  });
}

// ---------------- Events ----------------
async function eventsPage() {
  setRoot(h(`
    <section class="card">
      <h2 class="section-title">Events</h2>
      <div class="form-row">
        <input class="input" id="ev-name" placeholder="Name">
        <input class="input" id="ev-desc" placeholder="Description">
        <input class="input" id="ev-kw" placeholder="Keywords (comma-separated)">
        <button class="btn" id="ev-create">Create</button>
      </div>
      <div id="events-list">Loading…</div>
    </section>
  `));
  const el = document.getElementById('events-list');
  try {
    const items = await ndjson(API_BASE + '/api/event');
    el.innerHTML = renderEvents(items);
    wireEventActions();
  } catch (e) {
    el.textContent = 'Failed to load events';
  }
  document.getElementById('ev-create').addEventListener('click', async () => {
    const name = document.getElementById('ev-name').value.trim();
    const description = document.getElementById('ev-desc').value.trim();
    const kw = document.getElementById('ev-kw').value.trim();
    const keywords = kw ? kw.split(',').map(s => s.trim()).filter(Boolean) : [];
    try {
      const r = await fetch(API_BASE + '/api/event', {method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({name, description, keywords})});
      if (!r.ok) throw new Error('Create failed');
      alert('Event created');
      eventsPage();
    } catch (e) { alert('Event creation failed'); }
  });
}

function renderEvents(items) {
  return `
    <table class="table">
      <thead>
        <tr><th>ID</th><th>Name</th><th>Description</th><th>Keywords</th><th>Actions</th></tr>
      </thead>
      <tbody>
        ${items.map(ev => {
          const id = escapeHtml(getId(ev));
          return `
            <tr>
              <td class="meta">${id}</td>
              <td><input class="input" name="name" value="${escapeHtml(ev.name)}" data-id="${id}"></td>
              <td><input class="input" name="description" value="${escapeHtml(ev.description)}" data-id="${id}"></td>
              <td>${Array.isArray(ev.keywords) ? ev.keywords.map(k=>`<span class="badge">${escapeHtml(k)}</span>`).join(' ') : ''}</td>
              <td>
                <button class="btn secondary ev-del" data-id="${id}">Delete</button>
                <button class="btn ev-save" data-id="${id}">Save</button>
              </td>
            </tr>`;
        }).join('')}
      </tbody>
    </table>
  `;
}

function wireEventActions() {
  document.querySelectorAll('.ev-del').forEach(btn => {
    btn.addEventListener('click', async (e) => {
      const id = e.target.getAttribute('data-id');
      if (!confirm('Delete this event?')) return;
      try {
        const r = await fetch(API_BASE + '/api/event/' + encodeURIComponent(id), {method:'DELETE'});
        if (!r.ok) throw new Error('Delete failed');
        alert('Event deleted');
        eventsPage();
      } catch (e) { alert('Event deletion failed'); }
    });
  });
  document.querySelectorAll('.ev-save').forEach(btn => {
    btn.addEventListener('click', async (e) => {
      const id = e.target.getAttribute('data-id');
      const inputs = Array.from(document.querySelectorAll(`input[data-id="${CSS.escape(id)}"]`));
      const body = {};
      inputs.forEach(inp => body[inp.name] = inp.value || null);
      try {
        const r = await fetch(API_BASE + '/api/event/' + encodeURIComponent(id), {method:'PUT', headers:{'Content-Type':'application/json'}, body: JSON.stringify(body)});
        if (!r.ok) throw new Error('Update failed');
        alert('Event updated');
        eventsPage();
      } catch (e) { alert('Event update failed'); }
    });
  });
}

// ---------------- Map (OpenStreetMap) ----------------
async function mapPage() {
  setRoot(h(`
    <section class="card">
      <h2 class="section-title">GeoMap</h2>
      <p class="meta">Shows all medias with a known location.</p>
      <div id="map" class="map-wrap"></div>
    </section>
  `));
  if (typeof L === 'undefined') {
    const el = document.getElementById('map');
    el.textContent = 'Map library failed to load';
    return;
  }
  const map = L.map('map');
  const tiles = L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 19,
    attribution: '&copy; OpenStreetMap contributors'
  }).addTo(map);
  map.setView([0, 0], 2);

  // Use clustering with chunked loading to handle many points efficiently
  const cluster = L.markerClusterGroup({
    chunkedLoading: true,
    chunkInterval: 100,
    chunkDelay: 50,
    maxClusterRadius: 60,
    disableClusteringAtZoom: 17,
    spiderfyOnMaxZoom: false,
    showCoverageOnHover: false
  });
  map.addLayer(cluster);

  const items = await ndjson(API_BASE + '/api/media?filterHasLocation=true').catch(() => []);
  const bounds = L.latLngBounds();
  const markers = [];

  function latlngOf(loc) {
    if (!loc) return null;
    const lat = Number(loc.latitude);
    const lon = Number(loc.longitude);
    if (!Number.isFinite(lat) || !Number.isFinite(lon)) return null;
    return [lat, lon];
  }

  for (const m of items) {
    const ll = latlngOf(m.location || m.userDefinedLocation || (m.original && m.original.location));
    if (!ll) continue;
    const firstEvent = Array.isArray(m.events) && m.events.length ? (m.events[0].name || '') : '';
    const dt = m.shootDateTime || (m.original && m.original.cameraShootDateTime) || '';
    const desc = m.description || '';
    const title = escapeHtml([firstEvent, dt].filter(Boolean).join(' — '));
    const body = escapeHtml(String(desc));
    const rawKey = m.accessKey || '';
    const key = escapeHtml(rawKey);
    const imgTag = rawKey ? `<div style="margin-top:6px"><img class="map-thumb" src="${API_BASE + '/api/media/' + encodeURIComponent(rawKey) + '/normalized'}" alt="Media preview" loading="lazy"></div>` : '';
    const popup = `<div><div style="font-weight:600">${title || 'Media'}</div><div class="meta">${body}</div><div class="meta">Key: ${key}</div>${imgTag}</div>`;
    const marker = L.marker(ll).bindPopup(popup);
    markers.push(marker);
    bounds.extend(ll);
  }

  const added = markers.length;
  if (added > 0) {
    cluster.addLayers(markers);
    map.fitBounds(bounds.pad(0.1));
  } else {
    // No markers: center nicely on Europe-ish
    map.setView([20, 0], 2);
    const el = document.createElement('div');
    el.className = 'meta';
    el.style.marginTop = '8px';
    el.textContent = 'No located media found.';
    document.querySelector('.card').appendChild(el);
  }
}

// ---------------- Admin ----------------
function adminPage() {
  setRoot(h(`
    <section class="card">
      <h2 class="section-title">Administration</h2>
      <p class="meta">Run a full synchronization of all stores and their images.</p>
      <div class="form-row">
        <button class="btn" id="sync-btn">Synchronize</button>
        <span class="meta" id="sync-status"></span>
      </div>
    </section>
  `));
  const btn = document.getElementById('sync-btn');
  const status = document.getElementById('sync-status');
  btn.addEventListener('click', async () => {
    btn.disabled = true;
    status.textContent = 'Synchronizing…';
    try {
      const r = await fetch(API_BASE + '/api/admin/synchronize', { method: 'GET' });
      if (!r.ok) throw new Error('Sync failed');
      status.textContent = 'Synchronization started/completed successfully';
    } catch (e) {
      status.textContent = 'Synchronization failed';
    } finally {
      btn.disabled = false;
    }
  });
}

// ---------------- Router ----------------
function render() {
  const p = routePath();
  clearInterval(randomTimer);
  if (p === '/') return viewerPage();
  if (p.startsWith('/map')) return mapPage();
  if (p.startsWith('/owners')) return ownersPage();
  if (p.startsWith('/stores')) return storesPage();
  if (p.startsWith('/events')) return eventsPage();
  if (p.startsWith('/admin')) return adminPage();
  // default
  return viewerPage();
}

// Initial render
render();
