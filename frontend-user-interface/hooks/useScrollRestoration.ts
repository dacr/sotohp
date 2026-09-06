"use client";

// Restores a scrollable element's scroll offset after it remounts (e.g. navigating away to the
// Viewer and back to a person's face grid), and keeps it saved as the user scrolls. Session-scoped
// (sessionStorage) and keyed by whatever the caller passes - callers that render more than one
// distinct scrollable view under the same route (e.g. per-person, per-mode) should fold that into
// `key` so each gets independent scroll memory.
import { useEffect, useRef } from "react";

const PREFIX = "sotohp:scroll:";

export function useScrollRestoration<T extends HTMLElement>(key: string) {
  const ref = useRef<T | null>(null);

  // Restore. The saved offset can exceed the container's current scrollHeight right after mount -
  // content (react-query data, lazy-loaded images) is often still filling in - so a single
  // assignment can silently clamp to less than intended. Retry across a few frames instead of
  // guessing when "ready" is.
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    let target: number;
    try {
      const raw = sessionStorage.getItem(PREFIX + key);
      target = raw ? parseInt(raw, 10) : NaN;
    } catch {
      return;
    }
    if (!Number.isFinite(target) || target <= 0) return;
    let cancelled = false;
    let attempts = 0;
    function tryRestore() {
      if (cancelled) return;
      const node = ref.current;
      if (!node) return;
      node.scrollTop = target;
      attempts++;
      if (attempts < 15 && node.scrollTop < target - 2) requestAnimationFrame(tryRestore);
    }
    requestAnimationFrame(tryRestore);
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);

  // Save, coalesced to at most once per animation frame - `scroll` fires far more often than that.
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    let ticking = false;
    function onScroll() {
      if (ticking) return;
      ticking = true;
      requestAnimationFrame(() => {
        ticking = false;
        const node = ref.current;
        if (!node) return;
        try {
          sessionStorage.setItem(PREFIX + key, String(node.scrollTop));
        } catch {
          /* ignore */
        }
      });
    }
    el.addEventListener("scroll", onScroll, { passive: true });
    return () => el.removeEventListener("scroll", onScroll);
  }, [key]);

  return ref;
}
