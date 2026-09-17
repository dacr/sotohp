"use client";

// Restores a scrollable element's scroll offset after it remounts (e.g. navigating away to the
// Viewer and back to a person's face grid), and keeps it saved as the user scrolls. Session-scoped
// (sessionStorage) and keyed by whatever the caller passes - callers that render more than one
// distinct scrollable view under the same route (e.g. per-person, per-mode, per-cluster) should
// fold that into `key` so each gets independent scroll memory.
//
// The ref does not have to be attached the moment this hook's effect runs - some callers only
// mount their scrollable element once data has streamed in (an empty state renders instead until
// then). So attaching polls for the node for a few seconds rather than giving up immediately.
import { useEffect, useRef } from "react";

const PREFIX = "sotohp:scroll:";
const ATTACH_POLL_MS = 100;
const ATTACH_POLL_ATTEMPTS = 50; // ~5s - generous enough for a slow stream to populate the grid

export function useScrollRestoration<T extends HTMLElement>(key: string) {
  const ref = useRef<T | null>(null);

  useEffect(() => {
    let cancelled = false;
    let node: T | null = null;
    let attachTimer: ReturnType<typeof setTimeout> | null = null;
    let attachAttempts = 0;
    let saveTicking = false;

    function scheduleSave() {
      if (saveTicking) return;
      saveTicking = true;
      requestAnimationFrame(() => {
        saveTicking = false;
        if (!node) return;
        try {
          sessionStorage.setItem(PREFIX + key, String(node.scrollTop));
        } catch {
          /* ignore */
        }
      });
    }

    // The saved offset can exceed the container's current scrollHeight right after it mounts -
    // content (react-query data, lazy-loaded images) is often still filling in - so a single
    // assignment can silently clamp to less than intended. Retry across a few frames instead of
    // guessing when "ready" is.
    function restore() {
      let target: number;
      try {
        const raw = sessionStorage.getItem(PREFIX + key);
        target = raw ? parseInt(raw, 10) : NaN;
      } catch {
        return;
      }
      if (!Number.isFinite(target) || target <= 0) return;
      let attempts = 0;
      function tryRestore() {
        if (cancelled || !node) return;
        node.scrollTop = target;
        attempts++;
        if (attempts < 15 && node.scrollTop < target - 2) requestAnimationFrame(tryRestore);
      }
      requestAnimationFrame(tryRestore);
    }

    function attach() {
      if (cancelled) return;
      node = ref.current;
      if (!node) {
        attachAttempts++;
        if (attachAttempts < ATTACH_POLL_ATTEMPTS) attachTimer = setTimeout(attach, ATTACH_POLL_MS);
        return;
      }
      restore();
      node.addEventListener("scroll", scheduleSave, { passive: true });
    }
    attach();

    return () => {
      cancelled = true;
      if (attachTimer) clearTimeout(attachTimer);
      if (node) node.removeEventListener("scroll", scheduleSave);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);

  return ref;
}
