"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { useSyncStatus } from "../../hooks/useSyncStatus";
import { useTicker } from "../../hooks/useTicker";
import type { SynchronizeRun, SynchronizeStatus } from "../../lib/api-client";
import { ago, compactDuration, processingPercent, secondsSince, syncKind, syncTone } from "../../lib/sync-status";
import { showWarning } from "../../lib/toast";

const fmt = (n: number) => n.toLocaleString();

// Rate per minute of `count` items done in `seconds`, once there is enough to say something.
function perMinute(count: number, seconds: number): number | null {
  return seconds >= 3 ? Math.round((count / seconds) * 60) : null;
}

type StepState = "pending" | "active" | "done";

function Step({ state, title, children }: { state: StepState; title: string; children?: React.ReactNode }) {
  return (
    <div className={`sync-step sync-step--${state}`}>
      <span className="sync-step-mark">{state === "done" ? "✓" : ""}</span>
      <div className="sync-step-body">
        <strong>{title}</strong>
        {children}
      </div>
    </div>
  );
}

function RunningPanel({ status, now, onStop, stopping }: { status: SynchronizeStatus; now: number; onStop: () => void; stopping: boolean }) {
  const scanning = status.phase === "scanning";
  const elapsed = secondsSince(status.startedAt, now);
  const processingElapsed = secondsSince(status.processingStartedAt, now);
  const percent = processingPercent(status);
  const scanRate = scanning ? perMinute(status.checkedCount, elapsed) : null;
  const processRate = !scanning ? perMinute(status.processedCount, processingElapsed) : null;
  const store = status.currentStore ? ` · store ${status.storeIndex}/${status.storeCount} : ${status.currentStore}` : "";

  return (
    <>
      <div className="sync-status-header">
        <span className={`sync-dot sync-dot--${syncTone(status)}`} />
        <strong>{status.phase === "stopping" ? "Stopping…" : "Running"}</strong>
        <span className="muted-sm">
          {syncKind(status)} · for {compactDuration(elapsed)}
        </span>
        <span className="spacer" />
        <button className="btn btn-soft" disabled={stopping || status.phase === "stopping"} onClick={onStop} title="The media in progress is finished and indexed first, a later run takes care of the others">
          ■ Stop
        </button>
      </div>
      {status.phase === "stopping" && <div className="muted-sm sync-note">Finishing the media in progress, then the synchronization stops — a later run takes care of the remaining ones.</div>}

      <div className="sync-steps">
        <Step state={scanning ? "active" : "done"} title="Scan the stores">
          <div className="muted-sm">
            {fmt(status.checkedCount)} files checked{scanning ? store : ""} · <strong>{fmt(status.toProcessCount)}</strong> new
            {status.unreadableCount > 0 && <> · {fmt(status.unreadableCount)} unreadable</>}
            {scanRate !== null && <> · {fmt(scanRate)} files/min</>}
          </div>
        </Step>
        <Step state={scanning ? "pending" : "active"} title="Process the new medias">
          {scanning ? (
            <div className="muted-sm">Starts once the scan is done — its remaining time is then estimated</div>
          ) : (
            <>
              <div className="sync-progress">
                <div style={{ width: `${percent ?? 0}%` }} />
              </div>
              <div className="sync-stats-grid">
                <div className="sync-stat">
                  <span className="sync-stat-value">
                    {fmt(status.processedCount)} / {fmt(status.toProcessCount)}
                  </span>
                  <span className="muted-sm">processed{percent !== null ? ` · ${percent.toFixed(percent < 10 ? 1 : 0)}%` : ""}</span>
                </div>
                <div className="sync-stat">
                  <span className="sync-stat-value">{status.remainingSeconds != null ? `≈ ${compactDuration(status.remainingSeconds)}` : "–"}</span>
                  <span className="muted-sm">remaining</span>
                </div>
                {processRate !== null && (
                  <div className="sync-stat">
                    <span className="sync-stat-value">{fmt(processRate)}</span>
                    <span className="muted-sm">medias/min</span>
                  </div>
                )}
                <div className="sync-stat">
                  <span className="sync-stat-value">{fmt(status.indexedCount)}</span>
                  <span className="muted-sm">searchable</span>
                </div>
              </div>
              {status.currentMedia && (
                <div className="muted-sm sync-current">
                  Now : <span className="sync-path">{status.currentMedia.path}</span> ·{" "}
                  <Link href={`/?media=${encodeURIComponent(status.currentMedia.accessKey)}`}>show it</Link>
                </div>
              )}
            </>
          )}
        </Step>
      </div>
    </>
  );
}

function LastRun({ run, now }: { run: SynchronizeRun; now: number }) {
  const took = (new Date(run.finishedAt).getTime() - new Date(run.startedAt).getTime()) / 1000;
  const title = run.outcome === "failed" ? "Failed" : run.outcome === "stopped" ? "Stopped" : "Completed";
  const tone = run.outcome === "failed" ? "bad" : run.outcome === "stopped" || run.problemCount > 0 ? "warn" : "ok";
  return (
    <div className="sync-last-run">
      <div className="sync-status-header">
        <span className={`sync-dot sync-dot--${tone}`} />
        <strong>Last run : {title}</strong>
        <span className="muted-sm" title={new Date(run.finishedAt).toLocaleString()}>
          {run.addedThoseLastDays ? `quick sync (last ${run.addedThoseLastDays} days)` : "full sync"} · {ago(run.finishedAt, now)} · took {compactDuration(took)}
        </span>
      </div>
      <div className="muted-sm">
        {fmt(run.checkedCount)} files checked · {fmt(run.processedCount)} of {fmt(run.toProcessCount)} new medias processed
        {run.problemCount > 0 && <> · {fmt(run.problemCount)} problem(s)</>}
      </div>
      {run.failure && <div className="sync-alert sync-alert--bad">{run.failure}</div>}
    </div>
  );
}

export default function SettingsPage() {
  const { status, unreachable, start, starting, stop, stopping } = useSyncStatus();
  const [fastEnabled, setFastEnabled] = useState(true);
  const [days, setDays] = useState("20");
  const running = !!status?.running;
  const now = useTicker(true, running ? 1000 : 30_000);

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

  async function handleStop() {
    try {
      await stop();
    } catch {
      showWarning("Couldn't stop the synchronization.");
    }
  }

  const unavailableSteps = status?.unavailableSteps ?? [];
  const problems = status?.problems ?? [];

  return (
    <section className="page">
      <div className="settings-card">
        <h3>Synchronization</h3>
        <p className="muted">
          Keep your libraries up to date : new files found in the stores are added, then processed (miniatures, faces, AI captions, places…) and made searchable. A quick sync only looks at the files
          modified recently, a full sync at all of them.
        </p>
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
          <button className="primary" disabled={running || starting || !status} onClick={handleSync}>
            {fastEnabled ? "▷ Synchronize (quick)" : "▷ Synchronize (full)"}
          </button>
        </div>

        <div className="sync-status-panel">
          {unreachable && <div className="sync-alert sync-alert--bad">The server does not answer — the status is checked again every 15s.</div>}
          {status && running && <RunningPanel status={status} now={now} onStop={handleStop} stopping={stopping} />}
          {status && !running && !status.lastRun && (
            <div className="sync-status-header">
              <span className="sync-dot sync-dot--idle" />
              <strong>Idle</strong>
              <span className="muted-sm">no synchronization since the server started</span>
            </div>
          )}

          {running &&
            unavailableSteps.map((step) => (
              <div key={step} className="sync-alert sync-alert--warn">
                <strong>{step}</strong> skipped for the rest of this run : its server looks down. The medias processed meanwhile go without it — the dedicated CLI fills the gaps later.
              </div>
            ))}

          {status?.lastRun && <LastRun run={status.lastRun} now={now} />}

          {problems.length > 0 && (
            <details className="sync-problems" open={running}>
              <summary>
                {running ? "Problems so far" : "Problems of the last run"} : {fmt(status!.problemCount)}
                {status!.problemCount > problems.length && <span className="muted-sm"> (the {problems.length} most recent below)</span>}
              </summary>
              <ul>
                {problems.map((problem, index) => (
                  <li key={`${problem.at}-${index}`}>
                    <span className="muted-sm">{new Date(problem.at).toLocaleTimeString()}</span> {problem.message}
                    {problem.path && <div className="sync-path muted-sm">{problem.path}</div>}
                  </li>
                ))}
              </ul>
            </details>
          )}
        </div>
      </div>
    </section>
  );
}
