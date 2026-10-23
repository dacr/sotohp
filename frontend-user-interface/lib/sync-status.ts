import type { SynchronizeStatus } from "./api-client";

// What a synchronization status means for the user, shared by the header pill (components/
// SyncStatusPill.tsx) and the settings page, so both always tell the same story.

export type SyncTone = "ok" | "busy" | "warn" | "bad" | "idle";

/** Compact duration, e.g. 3d40m20s : only the non zero units. */
export function compactDuration(totalSeconds: number): string {
  let rest = Math.max(0, Math.round(totalSeconds));
  if (rest === 0) return "0s";
  const parts: string[] = [];
  for (const [unit, size] of [["d", 86400], ["h", 3600], ["m", 60], ["s", 1]] as const) {
    const value = Math.floor(rest / size);
    rest -= value * size;
    if (value > 0) parts.push(`${value}${unit}`);
  }
  return parts.join("");
}

export function secondsSince(iso: string | undefined, now = Date.now()): number {
  return iso ? Math.max(0, Math.floor((now - new Date(iso).getTime()) / 1000)) : 0;
}

/** "just now", "5m ago", "2h ago", "3d ago" - minute precision is plenty for a past run. */
export function ago(iso: string, now = Date.now()): string {
  const seconds = secondsSince(iso, now);
  if (seconds < 60) return "just now";
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 48) return `${hours}h ago`;
  return `${Math.floor(hours / 24)}d ago`;
}

const fmt = (n: number) => n.toLocaleString();

export function syncKind(status: SynchronizeStatus): string {
  return status.addedThoseLastDays ? `quick sync (last ${status.addedThoseLastDays} days)` : "full sync";
}

export function syncTone(status: SynchronizeStatus): SyncTone {
  if (status.running) {
    if (status.phase === "stopping" || (status.unavailableSteps ?? []).length > 0) return "warn";
    return "busy";
  }
  const run = status.lastRun;
  if (!run) return "idle";
  if (run.outcome === "failed") return "bad";
  if (run.outcome === "stopped" || run.problemCount > 0) return "warn";
  return "ok";
}

/** Processing progress in percent, or null when not processing yet. */
export function processingPercent(status: SynchronizeStatus): number | null {
  if (!status.running || status.phase === "scanning" || status.toProcessCount === 0) return null;
  return Math.min(100, (100 * status.processedCount) / status.toProcessCount);
}

/** The short label of the header pill. */
export function syncLabel(status: SynchronizeStatus, now = Date.now()): string {
  if (status.running) {
    switch (status.phase) {
      case "scanning":
        return `Scanning · ${fmt(status.checkedCount)} files`;
      case "stopping":
        return "Stopping…";
      default: {
        const eta = status.remainingSeconds != null ? ` · ≈ ${compactDuration(status.remainingSeconds)}` : "";
        return `Processing ${fmt(status.processedCount)}/${fmt(status.toProcessCount)}${eta}`;
      }
    }
  }
  const run = status.lastRun;
  if (!run) return "Sync idle";
  if (run.outcome === "failed") return `Sync failed · ${ago(run.finishedAt, now)}`;
  if (run.outcome === "stopped") return `Sync stopped · ${ago(run.finishedAt, now)}`;
  return `Synced ${ago(run.finishedAt, now)}`;
}

/** The multi-line tooltip of the header pill. */
export function syncTooltip(status: SynchronizeStatus, now = Date.now()): string {
  const lines: string[] = [];
  if (status.running) {
    lines.push(`${syncKind(status)[0].toUpperCase()}${syncKind(status).slice(1)} running for ${compactDuration(secondsSince(status.startedAt, now))}`);
    const store = status.storeCount > 1 && status.phase === "scanning" ? ` (store ${status.storeIndex}/${status.storeCount})` : "";
    lines.push(`${fmt(status.checkedCount)} files checked${store}, ${fmt(status.toProcessCount)} new`);
    if (status.phase !== "scanning") lines.push(`${fmt(status.processedCount)} of ${fmt(status.toProcessCount)} new medias processed`);
    if (status.remainingSeconds != null) lines.push(`Remaining time ≈ ${compactDuration(status.remainingSeconds)}`);
    else if (status.phase === "scanning") lines.push("Remaining time known once the scan is done");
    for (const step of status.unavailableSteps ?? []) lines.push(`${step} skipped : its server looks down`);
    if (status.problemCount > 0) lines.push(`${fmt(status.problemCount)} problem(s) so far`);
  } else if (status.lastRun) {
    const run = status.lastRun;
    lines.push(`Last ${run.addedThoseLastDays ? "quick" : "full"} sync ${run.outcome}, ${ago(run.finishedAt, now)} (took ${compactDuration((new Date(run.finishedAt).getTime() - new Date(run.startedAt).getTime()) / 1000)})`);
    lines.push(`${fmt(run.checkedCount)} files checked, ${fmt(run.processedCount)} of ${fmt(run.toProcessCount)} new medias processed`);
    if (run.failure) lines.push(`Failure : ${run.failure}`);
    if (run.problemCount > 0) lines.push(`${fmt(run.problemCount)} problem(s)`);
  } else {
    lines.push("No synchronization since the server started");
  }
  lines.push("Click for details");
  return lines.join("\n");
}
