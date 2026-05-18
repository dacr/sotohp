/**
 * Settings tab — currently just the synchronization controls.
 *
 * Public surface:
 *   - initSettings(ctx)        Wire the form/buttons once. ctx = { getApi }.
 *   - refreshSyncStatus()      Pull current sync status and update the UI.
 *   - startSyncPolling()       Begin polling sync status (called when tab activates).
 *   - stopSyncPolling()        Stop polling (called when leaving the tab).
 */

import { wireOnce } from '../lib/dom.js';
import { showWarning } from '../lib/toast.js';
import { clearMediaResolverCache } from '../lib/media-resolver.js';

let ctx = null;
const api = () => ctx.getApi();

let syncPollTimer = null;
let lastSyncRunning = false;

export function initSettings(context) {
  ctx = context;
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

  wireOnce(cbFast, 'change', () => {
    try { localStorage.setItem('settings.syncFastEnabled', cbFast.checked ? 'true' : 'false'); } catch {}
    updateButtonLabel();
  });
  wireOnce(inputDays, 'change', () => {
    const v = inputDays.value.trim();
    if (v) { try { localStorage.setItem('settings.syncDays', v); } catch {} }
  });
  wireOnce(btn, 'click', async () => {
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
      await api().synchronizeStart(daysParam);
      await refreshSyncStatus();
    } catch (e) {
      statusEl.textContent = 'Failed to start synchronization.';
      btn.disabled = false;
    }
  });
}

export async function refreshSyncStatus() {
  const statusEl = document.getElementById('sync-status');
  const btn = document.getElementById('btn-sync');
  try {
    const st = await api().synchronizeStatus();
    const running = !!st?.running;
    // When a sync run just finished, drop the resolver cache so newly indexed
    // media show up on next thumbnail load.
    if (lastSyncRunning && !running) clearMediaResolverCache();
    lastSyncRunning = running;
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

export function startSyncPolling() {
  if (syncPollTimer) return;
  syncPollTimer = setInterval(refreshSyncStatus, 3000);
}

export function stopSyncPolling() {
  if (syncPollTimer) { clearInterval(syncPollTimer); syncPollTimer = null; }
}

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
