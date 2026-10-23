"use client";

import Link from "next/link";
import { useSyncStatus } from "../hooks/useSyncStatus";
import { useTicker } from "../hooks/useTicker";
import { processingPercent, syncLabel, syncTone, syncTooltip } from "../lib/sync-status";

// Always visible synchronization status, in the header of every tab : what the running sync is
// doing and how long it should still take, or how the last one went. Hover for the details,
// click for the settings page. Hidden for users who aren't allowed to see it (admin endpoint).
export function SyncStatusPill() {
  const { status, refused, unreachable } = useSyncStatus();
  const running = !!status?.running;
  // a second while running (elapsed time in the tooltip), a minute otherwise ("synced 5m ago")
  const now = useTicker(!!status, running ? 1000 : 60_000);

  if (refused || (!status && !unreachable)) return null;

  if (unreachable) {
    return (
      <Link href="/settings" className="sync-pill" title="The sotohp server does not answer, the status is checked again every 15s">
        <span className="sync-dot sync-dot--bad" />
        <span className="sync-pill-label">Server unreachable</span>
      </Link>
    );
  }

  const percent = processingPercent(status!);
  return (
    <Link href="/settings" className="sync-pill" title={syncTooltip(status!, now)}>
      <span className={`sync-dot sync-dot--${syncTone(status!)}`} />
      <span className="sync-pill-label">{syncLabel(status!, now)}</span>
      {percent !== null && (
        <span className="sync-pill-bar" aria-hidden>
          <span style={{ width: `${percent}%` }} />
        </span>
      )}
    </Link>
  );
}
