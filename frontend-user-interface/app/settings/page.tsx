"use client";

import { useEffect, useState } from "react";
import { useSyncStatus } from "../../hooks/useSyncStatus";
import { showWarning } from "../../lib/toast";

function formatDuration(startedAt: string): string {
  const diffMs = Date.now() - new Date(startedAt).getTime();
  if (diffMs < 0) return "0s";
  const totalSeconds = Math.floor(diffMs / 1000);
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  let result = "";
  if (hours > 0) result += `${hours}h`;
  if (minutes > 0) result += `${minutes}m`;
  if (seconds > 0 || result === "") result += `${seconds}s`;
  return result;
}

// Forces a re-render every second while `active` — the elapsed-time and rate figures below are
// derived from `Date.now()`, not from data alone, so they need their own tick to feel live
// in between the ~1/s server-pushed refetches (see useSyncStatus).
function useTicker(active: boolean) {
  const [, setTick] = useState(0);
  useEffect(() => {
    if (!active) return;
    const id = setInterval(() => setTick((t) => t + 1), 1000);
    return () => clearInterval(id);
  }, [active]);
}

export default function SettingsPage() {
  const { status, start, starting } = useSyncStatus();
  const [fastEnabled, setFastEnabled] = useState(true);
  const [days, setDays] = useState("20");
  const running = !!status?.running;
  useTicker(running);

  useEffect(() => {
    try {
      const savedEnabled = localStorage.getItem("settings.syncFastEnabled");
      if (savedEnabled != null) setFastEnabled(savedEnabled === "true");
      const savedDays = localStorage.getItem("settings.syncDays");
      if (savedDays) setDays(savedDays);
    } catch {
      /* ignore */
    }
  }, []);

  function persistFast(v: boolean) {
    setFastEnabled(v);
    try {
      localStorage.setItem("settings.syncFastEnabled", String(v));
    } catch {
      /* ignore */
    }
  }
  function persistDays(v: string) {
    setDays(v);
    try {
      localStorage.setItem("settings.syncDays", v);
    } catch {
      /* ignore */
    }
  }

  async function handleSync() {
    let daysParam: number | undefined;
    if (fastEnabled) {
      const n = parseInt(days.trim(), 10);
      if (!Number.isFinite(n) || n <= 0) {
        showWarning("Please provide a valid number of days (> 0).");
        return;
      }
      daysParam = n;
    }
    await start(daysParam);
  }

  const processed = status?.processedCount ?? 0;
  const checked = status?.checkedCount ?? 0;
  const lastUpdate = status?.lastUpdated ? new Date(status.lastUpdated).toLocaleString() : "never";
  const elapsedSeconds = status?.startedAt ? Math.max(0, Math.floor((Date.now() - new Date(status.startedAt).getTime()) / 1000)) : 0;
  // checkedCount (every file looked at) is a steadier activity signal than processedCount (only
  // files actually needing a change) — a full sync over an already-up-to-date library can run for
  // a long time with processedCount stuck at 0, which used to read as "stuck", not "working".
  const rate = running && elapsedSeconds >= 3 ? Math.round((checked / elapsedSeconds) * 60) : null;

  return (
    <section className="page">
      <div className="settings-card">
        <h3>Synchronization</h3>
        <p className="muted">Keep your libraries up to date. You can run a quick sync that scans only recent additions, or a full sync over all stores.</p>
        <div className="settings-controls">
          <label className="checkbox">
            <input type="checkbox" checked={fastEnabled} onChange={(e) => persistFast(e.target.checked)} />
            <span>Quick scan: only files added in the last</span>
          </label>
          <div className="inline-input">
            <input list="sync-days-options" type="number" min={1} step={1} value={days} onChange={(e) => persistDays(e.target.value)} aria-label="Days" />
            <span className="suffix">days</span>
            <datalist id="sync-days-options">
              <option value="7" />
              <option value="20" />
              <option value="42" />
            </datalist>
          </div>
        </div>
        <div className="settings-actions">
          <button className="primary" disabled={running || starting} onClick={handleSync}>
            {fastEnabled ? "▷ Synchronize (quick)" : "▷ Synchronize (full)"}
          </button>
        </div>

        <div className="sync-status-panel">
          <div className="sync-status-header">
            <span className={`sync-dot${running ? " sync-dot--active" : ""}`} />
            <strong>{running ? "Running" : "Idle"}</strong>
            {running && status?.startedAt && <span className="muted-sm">for {formatDuration(status.startedAt)}</span>}
          </div>
          <div className="sync-stats-grid">
            <div className="sync-stat">
              <span className="sync-stat-value">{checked.toLocaleString()}</span>
              <span className="muted-sm">files scanned</span>
            </div>
            <div className="sync-stat">
              <span className="sync-stat-value">{processed.toLocaleString()}</span>
              <span className="muted-sm">files updated</span>
            </div>
            {rate !== null && (
              <div className="sync-stat">
                <span className="sync-stat-value">{rate.toLocaleString()}</span>
                <span className="muted-sm">files/min</span>
              </div>
            )}
          </div>
          <div className="muted-sm">Last update: {lastUpdate}</div>
        </div>
      </div>
    </section>
  );
}
