"use client";

import { useEffect, useState } from "react";

// Forces a re-render every `periodMs` while `active` — for figures derived from `Date.now()`
// (elapsed time, rates, "5m ago"), not from data alone, which need their own tick to stay live in
// between data refetches. Returns the current time, so the render reads one consistent `now`.
export function useTicker(active: boolean, periodMs = 1000): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!active) return;
    setNow(Date.now());
    const id = setInterval(() => setNow(Date.now()), periodMs);
    return () => clearInterval(id);
  }, [active, periodMs]);
  return now;
}
