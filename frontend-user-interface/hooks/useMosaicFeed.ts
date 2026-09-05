"use client";

// Bidirectional infinite-scroll feed for the mosaic tab. Ported as a bespoke hook rather than
// forced through a generic pagination helper (React Query's useInfiniteQuery doesn't fit a feed
// that both grows in two directions AND can jump to an arbitrary timestamp, discarding in-flight
// requests from before the jump) — see docs/internals plan. The one thing that *does* map
// cleanly onto React here (vs. the original's raw DOM node splicing) is keyed array rendering:
// CSS Grid's auto-fill placement still needs a prepended batch padded to a multiple of the
// current column count (see prependNewer) so existing tiles don't shift columns, but that's true
// regardless of who owns the DOM nodes — React's keyed reconciliation handles the actual
// insertion for free once the array + filler count are right.
import { useCallback, useEffect, useRef, useState, type RefObject } from "react";
import { useAuth } from "../lib/keycloak-auth";
import { timestampToScrollPosition } from "../lib/mosaic-timeline-math";
import type { Media } from "../lib/api-client";

const BATCH_SIZE = 50;
const SCROLL_THRESHOLD = 400;
const TIMESTAMP_STORAGE_KEY = "mosaic.selectedTimestamp";

export function useMosaicFeed(containerRef: RefObject<HTMLDivElement | null>, gridRef: RefObject<HTMLDivElement | null>, initialTimestamp?: string | null) {
  const { api } = useAuth();
  const [media, setMedia] = useState<Media[]>([]);
  const [fillerCount, setFillerCount] = useState(0);
  const [range, setRange] = useState<{ oldest: string | null; newest: string | null }>({ oldest: null, newest: null });
  const [indicatorText, setIndicatorText] = useState<string | null>(null);
  const [cursorRatio, setCursorRatio] = useState(0);

  const seenKeys = useRef<Set<string>>(new Set());
  const genRef = useRef(0);
  const loadingRef = useRef(false);
  const mediaRef = useRef<Media[]>([]);
  mediaRef.current = media;

  function claimKey(key: string): boolean {
    if (seenKeys.current.has(key)) return false;
    seenKeys.current.add(key);
    return true;
  }

  const getColumns = useCallback(() => {
    const grid = gridRef.current;
    if (!grid) return 1;
    const tmpl = getComputedStyle(grid).gridTemplateColumns || "";
    const tracks = tmpl.trim().split(/\s+/).filter((t) => t && t !== "none");
    return Math.max(1, tracks.length);
  }, [gridRef]);

  const streamBatch = useCallback(
    async (direction: "next" | "previous", referenceMedia: Media, count: number, onItem: (m: Media) => boolean | void): Promise<number> => {
      const controller = new AbortController();
      let appended = 0;
      let stopped = false;
      try {
        await api.mediasStreamFromKey(referenceMedia.accessKey, {
          backward: direction === "previous",
          limit: count,
          signal: controller.signal,
          onItem: (m) => {
            if (stopped || !m || m.accessKey === referenceMedia.accessKey) return;
            const cont = onItem(m);
            if (cont === false) {
              stopped = true;
              controller.abort();
              return;
            }
            appended++;
          },
        });
      } catch (e) {
        if (!(e instanceof DOMException && e.name === "AbortError") && !stopped) console.warn("mediaStream failed:", e);
      }
      return appended;
    },
    [api]
  );

  const updateCursor = useCallback(
    (ts: string | null) => {
      if (!ts || !range.oldest || !range.newest) return;
      setCursorRatio(timestampToScrollPosition(ts, range.oldest, range.newest));
    },
    [range]
  );

  const appendOlder = useCallback(async () => {
    if (loadingRef.current) return;
    const last = mediaRef.current[mediaRef.current.length - 1];
    if (!last) return;
    const startGen = genRef.current;
    loadingRef.current = true;
    let gotItems = false;
    const newItems: Media[] = [];
    try {
      await streamBatch("previous", last, BATCH_SIZE, (m) => {
        if (genRef.current !== startGen) return false;
        if (!claimKey(m.accessKey)) return true;
        newItems.push(m);
        gotItems = true;
        return true;
      });
      if (genRef.current === startGen && newItems.length > 0) setMedia((prev) => [...prev, ...newItems]);
    } finally {
      if (genRef.current === startGen) loadingRef.current = false;
    }
    if (gotItems && genRef.current === startGen) {
      const container = containerRef.current;
      if (container && container.scrollHeight <= container.clientHeight + SCROLL_THRESHOLD) appendOlder();
    }
  }, [containerRef, streamBatch]);

  const prependNewer = useCallback(async () => {
    if (loadingRef.current) return;
    const first = mediaRef.current[0];
    const container = containerRef.current;
    const grid = gridRef.current;
    if (!first || !container || !grid) return;
    const startGen = genRef.current;
    loadingRef.current = true;
    const cols = getColumns();
    const requested = Math.ceil(BATCH_SIZE / cols) * cols;
    const oldScrollHeight = container.scrollHeight;
    const batch: Media[] = [];
    try {
      await streamBatch("next", first, requested, (m) => {
        if (genRef.current !== startGen) return false;
        if (!claimKey(m.accessKey)) return true;
        batch.push(m);
        return true;
      });
      if (genRef.current !== startGen || batch.length === 0) return;
      // batch arrives oldest-first (walking "next" from `first`); reverse so the newest of the
      // batch lands at index 0, keeping the array newest-first throughout.
      const reversed = [...batch].reverse();
      setFillerCount((cols - (batch.length % cols)) % cols);
      setMedia((prev) => reversed.concat(prev));
      requestAnimationFrame(() => {
        const newScrollHeight = container.scrollHeight;
        container.scrollTop += newScrollHeight - oldScrollHeight;
      });
    } finally {
      if (genRef.current === startGen) loadingRef.current = false;
    }
  }, [containerRef, gridRef, getColumns, streamBatch]);

  const refreshAtTimestamp = useCallback(
    async (ts: string) => {
      if (!ts) return;
      genRef.current++;
      const startGen = genRef.current;
      loadingRef.current = true;
      seenKeys.current.clear();
      setFillerCount(0);
      setMedia([]);
      setIndicatorText(new Date(ts).toLocaleDateString());
      try {
        let startMedia: Media | null = null;
        try {
          startMedia = await api.getMedia("next", undefined, ts);
        } catch {
          /* try previous below */
        }
        if (genRef.current !== startGen) return;
        if (!startMedia) {
          try {
            startMedia = await api.getMedia("previous", undefined, ts);
          } catch {
            /* nothing available at all */
          }
        }
        if (genRef.current !== startGen || !startMedia) return;
        const anchor = startMedia;
        claimKey(anchor.accessKey);
        setMedia([anchor]);

        const olderCount = Math.floor(BATCH_SIZE / 2);
        const newerCount = BATCH_SIZE - olderCount;
        const olderItems: Media[] = [];
        const newerItems: Media[] = [];
        const olderTask = streamBatch("previous", anchor, olderCount, (m) => {
          if (genRef.current !== startGen) return false;
          if (!claimKey(m.accessKey)) return true;
          olderItems.push(m);
          return true;
        });
        const newerTask = streamBatch("next", anchor, newerCount, (m) => {
          if (genRef.current !== startGen) return false;
          if (!claimKey(m.accessKey)) return true;
          newerItems.push(m); // arrives in increasing-timestamp order from the anchor
          return true;
        });
        await Promise.all([olderTask, newerTask]);
        if (genRef.current !== startGen) return;
        setMedia([...newerItems].reverse().concat([anchor], olderItems));

        requestAnimationFrame(() => {
          if (genRef.current !== startGen) return;
          const grid = gridRef.current;
          const tile = grid?.querySelector(`[data-media-key="${CSS.escape(anchor.accessKey)}"]`);
          if (tile) tile.scrollIntoView({ block: "center" });
          else if (containerRef.current) containerRef.current.scrollTop = 0;
        });

        try {
          localStorage.setItem(TIMESTAMP_STORAGE_KEY, ts);
        } catch {
          /* ignore */
        }
        updateCursor(ts);
      } finally {
        if (genRef.current === startGen) {
          loadingRef.current = false;
          setTimeout(() => {
            if (genRef.current === startGen) setIndicatorText(null);
          }, 1000);
        }
      }
      if (genRef.current === startGen) {
        setTimeout(() => {
          if (genRef.current !== startGen) return;
          const container = containerRef.current;
          if (container && container.scrollHeight <= container.clientHeight + SCROLL_THRESHOLD) appendOlder();
        }, 80);
      }
    },
    [api, appendOlder, containerRef, gridRef, streamBatch, updateCursor]
  );

  function updateCursorFromScroll(scrollTop: number, scrollHeight: number, clientHeight: number) {
    if (mediaRef.current.length === 0) return;
    const pct = Math.max(0, Math.min(1, scrollTop / (scrollHeight - clientHeight || 1)));
    const idx = Math.floor(pct * (mediaRef.current.length - 1));
    const item = mediaRef.current[idx];
    if (item) updateCursor(item.shootDateTime || item.original?.cameraShootDateTime || item.bag?.timestamp || null);
  }

  // Initial range + first content load.
  useEffect(() => {
    (async () => {
      try {
        const [first, last] = await Promise.all([api.getMedia("first"), api.getMedia("last")]);
        const oldest = first.shootDateTime || first.original?.cameraShootDateTime || first.bag?.timestamp || null;
        const newest = last.shootDateTime || last.original?.cameraShootDateTime || last.bag?.timestamp || null;
        setRange({ oldest, newest });
        const saved = initialTimestamp || (() => {
          try {
            return localStorage.getItem(TIMESTAMP_STORAGE_KEY);
          } catch {
            return null;
          }
        })();
        await refreshAtTimestamp(saved || newest || "");
      } catch (e) {
        console.warn("Failed to load mosaic range", e);
      }
    })();
    // Runs once on mount only — refreshAtTimestamp/api identity changes shouldn't restart this.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return { media, fillerCount, range, indicatorText, cursorRatio, appendOlder, prependNewer, refreshAtTimestamp, updateCursorFromScroll };
}
