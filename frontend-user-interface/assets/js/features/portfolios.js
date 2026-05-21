/**
 * Portfolios feature.
 *
 * Public surface:
 *   - initPortfolios(ctx)        Wire the tab once. Pass a context with:
 *                                   { getApi, setActiveTab, showMedia }
 *   - ensurePortfoliosLoaded()   Load the portfolio list if not already loaded.
 *   - openAddToPortfolioModal(media)   Portfolio picker used from the viewer.
 *
 * Everything else (modals, asset viewer, mosaic) is private to this module.
 *
 * `getApi()` is called lazily because `app.js` reassigns the ApiClient
 * singleton during Keycloak init.
 */

import { escapeHtml, wireOnce } from '../lib/dom.js';
import { openModal } from '../lib/modal.js';
import { resolveMediaAccessKey } from '../lib/media-resolver.js';
import { showSuccess, showError, showWarning } from '../lib/toast.js';

let ctx = null;
let portfoliosLoaded = false;
let currentPortfolio = null;

const api = () => ctx.getApi();

// Orientation is serialized by the API as an integer ordinal of the
// Orientation enum (0 Horizontal, 2 Rotate180, 4 MirrorH+R270CW, 5 Rotate90CW,
// 6 MirrorH+R90CW, 7 Rotate270CW).
function orientationDegreesFor(o) {
  switch (o) {
    case 5:
    case 6:
      return 90;
    case 2:
      return 180;
    case 4:
    case 7:
      return 270;
    default:
      return 0;
  }
}

// ---------------------------------------------------------------------------
// Public entry points
// ---------------------------------------------------------------------------

export function initPortfolios(context) {
  ctx = context;
  wireOnce(document.getElementById('refresh-portfolios'), 'click', () => loadPortfolios({ force: true }));
  wireOnce(document.getElementById('create-portfolio'), 'click', () => openPortfolioCreateModal());
  wireOnce(document.getElementById('portfolio-back'), 'click', () => {
    showPortfoliosListView();
    loadPortfolios({ force: true });
  });
  wireOnce(document.getElementById('portfolio-view'), 'click', () => {
    if (!currentPortfolio || !(currentPortfolio.assets || []).length) {
      showWarning('No asset in this portfolio');
      return;
    }
    openPortfolioAssetViewer(currentPortfolio, 0);
  });
  wireOnce(document.getElementById('portfolio-edit'), 'click', () => {
    if (currentPortfolio) openPortfolioEditModal(currentPortfolio);
  });
  wireOnce(document.getElementById('portfolio-delete'), 'click', async () => {
    if (!currentPortfolio) return;
    if (!confirm(`Delete portfolio "${currentPortfolio.name}" and all its assets?`)) return;
    try {
      await api().deletePortfolio(currentPortfolio.id);
      portfoliosLoaded = false;
      showPortfoliosListView();
      await loadPortfolios({ force: true });
    } catch (e) {
      console.warn('deletePortfolio failed', e);
      showError('Failed to delete portfolio');
    }
  });
}

export function ensurePortfoliosLoaded() {
  try { if (!portfoliosLoaded) loadPortfolios({ force: false }); } catch {}
}

export function openAddToPortfolioModal(media) {
  if (!media) { showWarning('No media loaded'); return; }
  const handle = openModal({
    title: 'Add to portfolio',
    hideSave: true,
    cancelLabel: 'Close',
    focusSelector: '#atp-desc',
    body: `
      <div class="row">
        <div style="flex:1">
          <label>Description (optional)</label>
          <input type="text" id="atp-desc" placeholder="A note about this asset…" style="width:100%;margin-bottom:10px">
          <label>Select a portfolio</label>
          <ul id="atp-list" class="list" style="max-height:280px;overflow-y:auto;border:1px solid #e5e7eb;border-radius:6px;padding:4px;list-style:none;margin:4px 0">
            <li style="color:#9ca3af;padding:8px">Loading…</li>
          </ul>
          <button type="button" id="atp-new" style="margin-top:8px;background:#f3f4f6;border:1px solid #e5e7eb;padding:6px 10px;border-radius:6px;cursor:pointer">＋ Create new portfolio</button>
        </div>
      </div>`,
  });
  if (!handle) return;
  const { modal, close } = handle;

  async function addAsset(portfolio) {
    const desc = (modal.querySelector('#atp-desc')?.value || '').trim();
    const body = { originalId: media.original.id };
    if (desc) body.description = desc;
    try {
      await api().addPortfolioAsset(portfolio.id, body);
      portfoliosLoaded = false;
      showSuccess(`Added to "${portfolio.name}"`);
      close();
    } catch (e) {
      console.warn('addPortfolioAsset failed', e);
      showError('Failed to add to portfolio');
    }
  }

  async function refreshList() {
    const list = modal.querySelector('#atp-list');
    list.innerHTML = '';
    try {
      const portfolios = await api().listPortfolios();
      portfolios.sort((a, b) => (a.name || '').localeCompare(b.name || ''));
      if (portfolios.length === 0) {
        list.innerHTML = '<li style="color:#9ca3af;padding:8px">No portfolio yet — create one below.</li>';
        return;
      }
      for (const p of portfolios) {
        const li = document.createElement('li');
        li.style.cssText = 'padding:8px;border-bottom:1px solid #f3f4f6;cursor:pointer;display:flex;justify-content:space-between;align-items:center';
        li.innerHTML = `
          <div>
            <div style="font-weight:600">${escapeHtml(p.name) || '(no name)'}</div>
            <div style="font-size:11px;color:#888">${p.assetCount || 0} asset(s)</div>
          </div>
          <span style="color:#2563eb;font-size:12px">＋ Add</span>
        `;
        li.onmouseenter = () => { li.style.background = '#f9fafb'; };
        li.onmouseleave = () => { li.style.background = ''; };
        li.onclick = () => addAsset(p);
        list.appendChild(li);
      }
    } catch (e) {
      list.innerHTML = '<li style="color:#dc2626;padding:8px">Failed to load portfolios</li>';
    }
  }
  refreshList();
  modal.querySelector('#atp-new').addEventListener('click', () => {
    close();
    openPortfolioCreateModal(async (created) => {
      await addAsset(created);
    });
  });
}

// ---------------------------------------------------------------------------
// Internal: list / detail rendering
// ---------------------------------------------------------------------------

function buildAssetThumbImg(mediaAccessKey, asset, rotateDeg = 0) {
  const wrapper = document.createElement('div');
  wrapper.style.cssText = 'position:relative;width:100%;height:100%;overflow:hidden;';
  const img = new Image();
  img.src = api().mediaNormalizedUrl(mediaAccessKey);
  img.loading = 'lazy';
  img.decoding = 'async';
  img.draggable = false;
  if (asset && asset.selectedBox && asset.selectedBox.width > 0 && asset.selectedBox.height > 0) {
    const b = asset.selectedBox;
    const wPct = (1 / b.width) * 100;
    const hPct = (1 / b.height) * 100;
    const lPct = -(b.x / b.width) * 100;
    const tPct = -(b.y / b.height) * 100;
    img.style.cssText = `position:absolute;width:${wPct}%;height:${hPct}%;left:${lPct}%;top:${tPct}%;object-fit:cover;display:block;`;
  } else {
    img.style.cssText = 'position:absolute;inset:0;width:100%;height:100%;object-fit:cover;display:block;';
  }
  if (rotateDeg) {
    img.style.transform = `rotate(${rotateDeg}deg)`;
    img.style.transformOrigin = '50% 50%';
  }
  wrapper.appendChild(img);
  return wrapper;
}

async function renderPortfolioMosaic(container, assets) {
  if (!container) return;
  const preview = (assets || []).slice(0, 4);
  if (preview.length === 0) return;
  const grid = document.createElement('div');
  grid.style.cssText = 'position:absolute;inset:0;display:grid;gap:1px;background:#e5e7eb;';
  if (preview.length === 1) {
    grid.style.gridTemplateColumns = '1fr';
    grid.style.gridTemplateRows = '1fr';
  } else if (preview.length === 2) {
    grid.style.gridTemplateColumns = '1fr 1fr';
    grid.style.gridTemplateRows = '1fr';
  } else {
    grid.style.gridTemplateColumns = '1fr 1fr';
    grid.style.gridTemplateRows = '1fr 1fr';
  }
  const cells = preview.map((asset, i) => {
    const cell = document.createElement('div');
    cell.style.cssText = 'background:#f3f4f6;overflow:hidden;';
    if (preview.length === 3 && i === 0) cell.style.gridColumn = '1 / span 2';
    grid.appendChild(cell);
    return { cell, asset };
  });
  const fallback = container.querySelector('.pf-thumb-fallback');
  if (fallback) fallback.style.display = 'none';
  container.appendChild(grid);
  await Promise.all(cells.map(async ({ cell, asset }) => {
    const key = await resolveMediaAccessKey(api(), asset.originalId);
    if (key) cell.appendChild(buildAssetThumbImg(key, asset));
  }));
}

function showPortfoliosListView() {
  const lv = document.getElementById('portfolios-list-view');
  const dv = document.getElementById('portfolios-detail-view');
  if (lv) lv.style.display = '';
  if (dv) dv.style.display = 'none';
  currentPortfolio = null;
}

function showPortfolioDetailView() {
  const lv = document.getElementById('portfolios-list-view');
  const dv = document.getElementById('portfolios-detail-view');
  if (lv) lv.style.display = 'none';
  if (dv) dv.style.display = '';
}

async function loadPortfolios(options = {}) {
  const { force = false } = options;
  const list = document.getElementById('portfolios-list');
  if (!list) return;
  if (!force && portfoliosLoaded && list.children.length > 0) return;
  list.innerHTML = '';
  try {
    const portfolios = await api().listPortfolios();
    portfolios.sort((a, b) => (a.name || '').localeCompare(b.name || ''));
    if (portfolios.length === 0) {
      list.innerHTML = '<li style="color:#9ca3af;text-align:center;padding:24px">No portfolio yet. Click "Create portfolio" to start.</li>';
    }
    for (const p of portfolios) {
      const li = document.createElement('li');
      li.dataset.portfolioId = p.id;
      li.innerHTML = `
        <div class="pf-thumb list-thumb">
          <span class="pf-thumb-fallback">${p.assetCount || 0} asset(s)</span>
        </div>
        <h4 style="margin:0 0 4px 0;">${escapeHtml(p.name) || '(no name)'}</h4>
        <div style="font-size:12px;color:#555">${escapeHtml(p.description)}</div>
      `;
      li.style.cursor = 'pointer';
      li.onclick = () => openPortfolioDetail(p.id);
      list.appendChild(li);
      renderPortfolioMosaic(li.querySelector('.pf-thumb'), p.assets || []);
    }
    portfoliosLoaded = true;
  } catch (e) {
    console.warn('loadPortfolios failed', e);
    list.innerHTML = '<li>Failed to load portfolios</li>';
  }
}

async function openPortfolioDetail(portfolioId) {
  try {
    const portfolio = await api().getPortfolio(portfolioId);
    currentPortfolio = portfolio;
    document.getElementById('portfolio-detail-title').textContent = portfolio.name || '';
    document.getElementById('portfolio-detail-description').textContent = portfolio.description || '';
    const list = document.getElementById('portfolio-assets-list');
    list.innerHTML = '';
    const assets = Array.isArray(portfolio.assets) ? portfolio.assets : [];
    if (assets.length === 0) {
      list.innerHTML = '<li style="color:#9ca3af;text-align:center;padding:24px">No asset yet. Open a photo in the Viewer and use "Add to portfolio…" to add one.</li>';
    }
    for (let i = 0; i < assets.length; i++) {
      const asset = assets[i];
      const li = document.createElement('li');
      li.style.position = 'relative';
      li.dataset.originalId = asset.originalId;
      li.dataset.assetIndex = String(i);
      const cropBadge = asset.selectedBox ? '<span class="icon-badge">✂ Cropped</span>' : '';
      const descText = asset.description
        ? `<span style="color:#374151;font-style:italic;">${escapeHtml(asset.description)}</span>`
        : '<span style="color:#9ca3af;font-style:italic;">No description</span>';
      li.innerHTML = `
        <div class="pf-asset-thumb list-thumb">
          <span class="pf-thumb-placeholder">Loading…</span>
          ${cropBadge}
        </div>
        <div class="pf-asset-row" style="display:flex;align-items:center;gap:6px;font-size:12px;margin:4px 0 2px 0;">
          <div class="pf-asset-desc" style="flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">${descText}</div>
          <button class="pf-asset-icon pf-asset-view" title="Open in viewer tab" aria-label="Open in viewer tab">👁</button>
          <button class="pf-asset-icon pf-asset-edit" title="Edit description" aria-label="Edit description">✎</button>
          <button class="pf-asset-icon pf-asset-remove" title="Remove from portfolio" aria-label="Remove from portfolio">🗑</button>
        </div>
      `;
      list.appendChild(li);
      li.querySelector('.pf-asset-remove').onclick = async (e) => {
        e.stopPropagation();
        if (!confirm('Remove this asset from the portfolio?')) return;
        try {
          await api().removePortfolioAsset(portfolioId, asset);
          portfoliosLoaded = false;
          await openPortfolioDetail(portfolioId);
        } catch (err) { console.warn('Remove asset failed', err); showError('Failed to remove asset'); }
      };
      li.querySelector('.pf-asset-edit').onclick = (e) => {
        e.stopPropagation();
        openAssetEditModal(portfolioId, asset);
      };
      (async () => {
        const key = await resolveMediaAccessKey(api(), asset.originalId);
        if (!key) return;
        let rotateDeg = 0;
        try { const m = await api().getMediaByKey(key); rotateDeg = orientationDegreesFor(m && m.orientation); } catch {}
        const thumb = li.querySelector('.pf-asset-thumb');
        const placeholder = thumb?.querySelector('.pf-thumb-placeholder');
        if (placeholder) placeholder.remove();
        if (thumb) {
          thumb.style.background = 'transparent';
          thumb.insertBefore(buildAssetThumbImg(key, asset, rotateDeg), thumb.firstChild);
        }
        li.style.cursor = 'pointer';
        li.onclick = (e) => {
          if (e.target.closest('.pf-asset-remove')) return;
          if (e.target.closest('.pf-asset-edit')) return;
          if (e.target.closest('.pf-asset-view')) return;
          openPortfolioAssetViewer(currentPortfolio, i);
        };
        const viewBtn = li.querySelector('.pf-asset-view');
        if (viewBtn) {
          viewBtn.onclick = async (e) => {
            e.stopPropagation();
            try {
              ctx.setActiveTab('viewer');
              const media = await api().getMediaByKey(key);
              ctx.showMedia(media);
            } catch (err) { console.warn('Open in viewer failed', err); showError('Failed to open in viewer'); }
          };
        }
      })();
    }
    showPortfolioDetailView();
  } catch (e) {
    console.warn('Open portfolio detail failed', e);
    showError('Failed to open portfolio');
  }
}

// ---------------------------------------------------------------------------
// Internal: modals + asset viewer
// ---------------------------------------------------------------------------

function openPortfolioCreateModal(onCreated) {
  openModal({
    title: 'Create portfolio',
    saveLabel: 'Create',
    focusSelector: '#pfc-name',
    body: `
      <div class="row">
        <div>
          <label>Name</label>
          <input type="text" id="pfc-name" required>
          <label class="form-label">Description (optional)</label>
          <input type="text" id="pfc-desc">
        </div>
      </div>`,
    onSave: async ({ modal }) => {
      const name = modal.querySelector('#pfc-name').value.trim();
      const description = modal.querySelector('#pfc-desc').value.trim();
      if (!name) { showWarning('Portfolio name is required'); return false; }
      const body = { name };
      if (description) body.description = description;
      try {
        const created = await api().createPortfolio(body);
        portfoliosLoaded = false;
        if (typeof onCreated === 'function') {
          try { await onCreated(created); } catch {}
        } else {
          await loadPortfolios({ force: true });
        }
      } catch (e) {
        console.warn('createPortfolio failed', e);
        showError('Failed to create portfolio');
        return false;
      }
    },
  });
}

function openPortfolioEditModal(portfolio) {
  openModal({
    title: 'Edit portfolio',
    focusSelector: '#pfe-name',
    body: () => {
      const tmp = document.createElement('div');
      tmp.innerHTML = `
        <div class="row">
          <div>
            <label>Name</label>
            <input type="text" id="pfe-name" required>
            <label class="form-label">Description (optional)</label>
            <input type="text" id="pfe-desc">
          </div>
        </div>`;
      tmp.querySelector('#pfe-name').value = portfolio.name || '';
      tmp.querySelector('#pfe-desc').value = portfolio.description || '';
      return tmp;
    },
    onSave: async ({ modal }) => {
      const name = modal.querySelector('#pfe-name').value.trim();
      const description = modal.querySelector('#pfe-desc').value.trim();
      if (!name) { showWarning('Portfolio name is required'); return false; }
      const body = { name };
      if (description) body.description = description;
      try {
        await api().updatePortfolio(portfolio.id, body);
        portfoliosLoaded = false;
        await openPortfolioDetail(portfolio.id);
      } catch (e) {
        console.warn('updatePortfolio failed', e);
        showError('Failed to update portfolio');
        return false;
      }
    },
  });
}

function openPortfolioAssetViewer(portfolio, startIndex) {
  if (document.querySelector('.portfolio-asset-viewer')) return;
  const assets = (portfolio && portfolio.assets) || [];
  if (assets.length === 0) return;
  let idx = Math.max(0, Math.min(startIndex || 0, assets.length - 1));
  const overlay = document.createElement('div');
  overlay.className = 'portfolio-asset-viewer';
  overlay.style.cssText = 'position:fixed;inset:0;background:rgba(0,0,0,0.92);z-index:1000;display:flex;flex-direction:column;align-items:center;justify-content:center;';
  overlay.innerHTML = `
    <button class="pav-close" title="Close (Esc)" style="position:absolute;top:16px;right:16px;background:rgba(255,255,255,0.1);color:#fff;border:none;width:40px;height:40px;border-radius:20px;cursor:pointer;font-size:20px;line-height:1;">✕</button>
    <button class="pav-prev" title="Previous (←)" style="position:absolute;left:16px;top:50%;transform:translateY(-50%);background:rgba(255,255,255,0.1);color:#fff;border:none;width:48px;height:48px;border-radius:24px;cursor:pointer;font-size:24px;line-height:1;">‹</button>
    <button class="pav-next" title="Next (→)" style="position:absolute;right:16px;top:50%;transform:translateY(-50%);background:rgba(255,255,255,0.1);color:#fff;border:none;width:48px;height:48px;border-radius:24px;cursor:pointer;font-size:24px;line-height:1;">›</button>
    <div class="pav-stage" style="position:relative;width:90vw;height:80vh;display:flex;align-items:center;justify-content:center;color:#9ca3af;font-size:14px;"></div>
    <div class="pav-info" style="color:#e5e7eb;font-size:13px;margin-top:12px;text-align:center;max-width:80vw;line-height:1.4;"></div>
  `;
  document.body.appendChild(overlay);

  const stage = overlay.querySelector('.pav-stage');
  const info = overlay.querySelector('.pav-info');
  let currentImg = null;
  let token = 0;

  async function render() {
    const myToken = ++token;
    const asset = assets[idx];
    if (!asset) return;
    if (currentImg) { try { currentImg.src = ''; } catch {} currentImg = null; }
    stage.innerHTML = '<span>Loading original…</span>';
    const descPart = asset.description ? ` — ${escapeHtml(asset.description)}` : '';
    const cropPart = asset.selectedBox ? ' · ✂ cropped' : '';
    info.innerHTML = `${idx + 1} / ${assets.length}${cropPart}${descPart}`;
    const key = await resolveMediaAccessKey(api(), asset.originalId);
    if (myToken !== token) return;
    if (!key) { stage.innerHTML = '<span class="text-danger">Failed to load image</span>'; return; }
    // Fetch the media to read the user-defined orientation override (best effort)
    let rotateDeg = 0;
    try {
      const m = await api().getMediaByKey(key);
      if (myToken !== token) return;
      rotateDeg = orientationDegreesFor(m && m.orientation);
    } catch {}
    const img = new Image();
    currentImg = img;
    img.draggable = false;
    img.onload = () => {
      if (myToken !== token) return;
      stage.innerHTML = '';
      const stageW = stage.clientWidth;
      const stageH = stage.clientHeight;
      const b = (asset.selectedBox && asset.selectedBox.width > 0 && asset.selectedBox.height > 0) ? asset.selectedBox : null;
      const cropNatW = b ? img.naturalWidth * b.width : img.naturalWidth;
      const cropNatH = b ? img.naturalHeight * b.height : img.naturalHeight;
      // For 90/270 rotations, the visible cropped region's aspect ratio inverts
      const swapAspect = rotateDeg === 90 || rotateDeg === 270;
      const cropAspect = swapAspect ? (cropNatH / cropNatW) : (cropNatW / cropNatH);
      const stageAspect = stageW / stageH;
      let wrapW, wrapH;
      if (cropAspect > stageAspect) { wrapW = stageW; wrapH = stageW / cropAspect; }
      else { wrapH = stageH; wrapW = stageH * cropAspect; }
      const wrapper = document.createElement('div');
      wrapper.style.cssText = `position:relative;width:${wrapW}px;height:${wrapH}px;overflow:hidden;background:#000;`;
      // Inner box that handles the rotation while keeping crop math intact
      const rotator = document.createElement('div');
      // For 90/270, swap inner box dimensions so the rotated content matches the wrapper
      const innerW = swapAspect ? wrapH : wrapW;
      const innerH = swapAspect ? wrapW : wrapH;
      const tx = (wrapW - innerW) / 2;
      const ty = (wrapH - innerH) / 2;
      rotator.style.cssText = `position:absolute;left:${tx}px;top:${ty}px;width:${innerW}px;height:${innerH}px;transform-origin:50% 50%;transform:rotate(${rotateDeg}deg);`;
      if (b) {
        const wPct = (1 / b.width) * 100;
        const hPct = (1 / b.height) * 100;
        const lPct = -(b.x / b.width) * 100;
        const tPct = -(b.y / b.height) * 100;
        img.style.cssText = `position:absolute;width:${wPct}%;height:${hPct}%;left:${lPct}%;top:${tPct}%;display:block;`;
      } else {
        img.style.cssText = 'position:absolute;inset:0;width:100%;height:100%;display:block;';
      }
      rotator.appendChild(img);
      wrapper.appendChild(rotator);
      stage.appendChild(wrapper);
    };
    img.onerror = () => { if (myToken === token) stage.innerHTML = '<span class="text-danger">Failed to load image</span>'; };
    img.src = api().mediaOriginalUrl(key);
  }

  function close() { overlay.remove(); document.removeEventListener('keydown', onKey); window.removeEventListener('resize', onResize); }
  function prev() { idx = (idx - 1 + assets.length) % assets.length; render(); }
  function next() { idx = (idx + 1) % assets.length; render(); }
  function onKey(e) {
    if (e.key === 'Escape') close();
    else if (e.key === 'ArrowLeft') prev();
    else if (e.key === 'ArrowRight') next();
  }
  function onResize() { render(); }

  overlay.querySelector('.pav-close').onclick = close;
  overlay.querySelector('.pav-prev').onclick = prev;
  overlay.querySelector('.pav-next').onclick = next;
  overlay.addEventListener('click', (e) => { if (e.target === overlay) close(); });
  document.addEventListener('keydown', onKey);
  window.addEventListener('resize', onResize);

  render();
}

function openAssetEditModal(portfolioId, asset) {
  let cropBox = asset.selectedBox ? { ...asset.selectedBox } : null;
  let drawMode = false;
  let img = null;
  let overlayEl = null;
  let stage, status, drawBtn, removeBtn;
  const onResize = () => renderOverlay();

  const handle = openModal({
    title: 'Edit asset',
    modalAttrs: 'max-width:760px;width:90%',
    focusSelector: '#ae-desc',
    body: `
      <div class="row" style="flex-direction:column;gap:12px;">
        <div>
          <label>Description (optional)</label>
          <input type="text" id="ae-desc" placeholder="A note about this asset…">
        </div>
        <div>
          <label>Crop region (drag to move, drag corners to resize)</label>
          <div id="ae-crop-controls" style="display:flex;gap:8px;margin:4px 0">
            <button type="button" id="ae-crop-draw" class="btn btn-soft btn-sm">＋ Draw new crop</button>
            <button type="button" id="ae-crop-remove" class="btn btn-danger-soft btn-sm">🗑 Remove crop</button>
            <span id="ae-crop-status" style="font-size:11px;color:#6b7280;align-self:center"></span>
          </div>
          <div id="ae-stage" style="position:relative;width:100%;background:#0f172a;border-radius:6px;overflow:hidden;display:flex;align-items:center;justify-content:center;min-height:200px;">
            <span id="ae-stage-loading" style="color:#9ca3af;font-size:13px">Loading image…</span>
          </div>
        </div>
      </div>`,
    onSave: async ({ modal }) => {
      const description = modal.querySelector('#ae-desc').value.trim();
      const newAsset = { originalId: asset.originalId };
      if (cropBox) newAsset.selectedBox = cropBox;
      if (description) newAsset.description = description;
      const oldAssetBody = { originalId: asset.originalId };
      if (asset.selectedBox) oldAssetBody.selectedBox = asset.selectedBox;
      if (asset.description) oldAssetBody.description = asset.description;
      try {
        await api().updatePortfolioAsset(portfolioId, oldAssetBody, newAsset);
        portfoliosLoaded = false;
        await openPortfolioDetail(portfolioId);
      } catch (e) {
        console.warn('updatePortfolioAsset failed', e);
        showError('Failed to update asset');
        return false;
      }
    },
    onClose: () => { window.removeEventListener('resize', onResize); },
  });
  if (!handle) return;
  const { modal } = handle;
  modal.querySelector('#ae-desc').value = asset.description || '';
  stage = modal.querySelector('#ae-stage');
  status = modal.querySelector('#ae-crop-status');
  drawBtn = modal.querySelector('#ae-crop-draw');
  removeBtn = modal.querySelector('#ae-crop-remove');

  function updateStatus() {
    if (!cropBox) status.textContent = 'No crop — full image';
    else status.textContent = `x: ${cropBox.x.toFixed(3)}, y: ${cropBox.y.toFixed(3)}, w: ${cropBox.width.toFixed(3)}, h: ${cropBox.height.toFixed(3)}`;
    removeBtn.disabled = !cropBox;
    drawBtn.classList.toggle('is-active', drawMode);
    drawBtn.textContent = drawMode ? '× Cancel draw' : '＋ Draw new crop';
  }
  function renderOverlay() {
    if (overlayEl) overlayEl.remove();
    overlayEl = null;
    if (!cropBox || !img) return;
    const rect = img.getBoundingClientRect();
    const stageRect = stage.getBoundingClientRect();
    const offX = rect.left - stageRect.left;
    const offY = rect.top - stageRect.top;
    overlayEl = document.createElement('div');
    overlayEl.style.cssText = `position:absolute;left:${offX + cropBox.x * rect.width}px;top:${offY + cropBox.y * rect.height}px;width:${cropBox.width * rect.width}px;height:${cropBox.height * rect.height}px;border:2px solid #2563eb;box-shadow:0 0 0 9999px rgba(0,0,0,0.45);cursor:move;`;
    stage.appendChild(overlayEl);
    const handles = [['nw',0,0,'nwse'], ['ne',1,0,'nesw'], ['sw',0,1,'nesw'], ['se',1,1,'nwse']];
    for (const [key, fx, fy, cur] of handles) {
      const h = document.createElement('div');
      h.dataset.handle = key;
      h.style.cssText = `position:absolute;left:${fx*100}%;top:${fy*100}%;width:12px;height:12px;margin-left:-6px;margin-top:-6px;background:#fff;border:2px solid #2563eb;border-radius:2px;cursor:${cur}-resize;`;
      overlayEl.appendChild(h);
    }
    overlayEl.addEventListener('pointerdown', onMoveStart);
    overlayEl.querySelectorAll('[data-handle]').forEach(h => h.addEventListener('pointerdown', onResizeStart));
  }
  function clamp(v, min, max) { return Math.max(min, Math.min(max, v)); }
  function onMoveStart(e) {
    if (e.target !== overlayEl) return;
    e.preventDefault(); e.stopPropagation();
    const rect = img.getBoundingClientRect();
    const startX = e.clientX, startY = e.clientY;
    const orig = { ...cropBox };
    function onMove(ev) {
      const dx = (ev.clientX - startX) / rect.width;
      const dy = (ev.clientY - startY) / rect.height;
      cropBox = {
        x: clamp(orig.x + dx, 0, 1 - orig.width),
        y: clamp(orig.y + dy, 0, 1 - orig.height),
        width: orig.width,
        height: orig.height,
      };
      renderOverlay(); updateStatus();
    }
    function onUp() { window.removeEventListener('pointermove', onMove); window.removeEventListener('pointerup', onUp); }
    window.addEventListener('pointermove', onMove);
    window.addEventListener('pointerup', onUp);
  }
  function onResizeStart(e) {
    e.preventDefault(); e.stopPropagation();
    const which = e.currentTarget.dataset.handle;
    const rect = img.getBoundingClientRect();
    const startX = e.clientX, startY = e.clientY;
    const orig = { ...cropBox };
    const MIN = 0.02;
    function onMove(ev) {
      const dx = (ev.clientX - startX) / rect.width;
      const dy = (ev.clientY - startY) / rect.height;
      let x = orig.x, y = orig.y, w = orig.width, h = orig.height;
      if (which.includes('w')) { const nx = clamp(orig.x + dx, 0, orig.x + orig.width - MIN); w = orig.width + (orig.x - nx); x = nx; }
      if (which.includes('e')) { w = clamp(orig.width + dx, MIN, 1 - orig.x); }
      if (which.includes('n')) { const ny = clamp(orig.y + dy, 0, orig.y + orig.height - MIN); h = orig.height + (orig.y - ny); y = ny; }
      if (which.includes('s')) { h = clamp(orig.height + dy, MIN, 1 - orig.y); }
      cropBox = { x, y, width: w, height: h };
      renderOverlay(); updateStatus();
    }
    function onUp() { window.removeEventListener('pointermove', onMove); window.removeEventListener('pointerup', onUp); }
    window.addEventListener('pointermove', onMove);
    window.addEventListener('pointerup', onUp);
  }
  function enableDrawMode() {
    drawMode = true;
    updateStatus();
    const startHandler = (e) => {
      if (!drawMode || !img) return;
      if (!stage.contains(e.target)) return;
      e.preventDefault();
      const rect = img.getBoundingClientRect();
      const startXRel = clamp((e.clientX - rect.left) / rect.width, 0, 1);
      const startYRel = clamp((e.clientY - rect.top) / rect.height, 0, 1);
      function onMove(ev) {
        const xr = clamp((ev.clientX - rect.left) / rect.width, 0, 1);
        const yr = clamp((ev.clientY - rect.top) / rect.height, 0, 1);
        const x = Math.min(startXRel, xr);
        const y = Math.min(startYRel, yr);
        const width = Math.abs(xr - startXRel);
        const height = Math.abs(yr - startYRel);
        if (width >= 0.005 && height >= 0.005) {
          cropBox = { x, y, width, height };
          renderOverlay(); updateStatus();
        }
      }
      function onUp() {
        window.removeEventListener('pointermove', onMove);
        window.removeEventListener('pointerup', onUp);
        stage.removeEventListener('pointerdown', startHandler);
        drawMode = false;
        updateStatus();
      }
      window.addEventListener('pointermove', onMove);
      window.addEventListener('pointerup', onUp);
    };
    stage.addEventListener('pointerdown', startHandler);
  }
  drawBtn.addEventListener('click', () => {
    if (drawMode) { drawMode = false; updateStatus(); return; }
    enableDrawMode();
  });
  removeBtn.addEventListener('click', () => { cropBox = null; renderOverlay(); updateStatus(); });

  (async () => {
    const key = await resolveMediaAccessKey(api(), asset.originalId);
    if (!key) {
      const loading = modal.querySelector('#ae-stage-loading');
      if (loading) loading.textContent = 'Failed to load image';
      return;
    }
    img = new Image();
    img.src = api().mediaNormalizedUrl(key);
    img.style.cssText = 'display:block;max-width:100%;max-height:50vh;width:auto;height:auto;user-select:none;-webkit-user-drag:none;';
    img.draggable = false;
    img.onload = () => {
      const loading = modal.querySelector('#ae-stage-loading');
      if (loading) loading.remove();
      renderOverlay();
    };
    stage.appendChild(img);
  })();

  window.addEventListener('resize', onResize);
  updateStatus();
}
