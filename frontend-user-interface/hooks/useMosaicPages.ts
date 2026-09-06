"use client";

// Windowed media store behind the mosaic grid.
//
// The mosaic used to keep every media it had ever streamed in one ever-growing array, so a long
// scrolling session ended with tens of thousands of live <img> nodes and their decoded bitmaps
// pinned in memory, and no way to jump anywhere without walking there. This replaces that with a
// bounded page cache addressed by absolute offset:
//
//   - a page is `timeline.step` consecutive medias, and page `p` starts exactly at the anchor key
//     `timeline.anchors[p]` — so any page is one request, whether it is the first or the 900th;
//   - only pages near the viewport are resident. Pages that fall out are dropped, their tiles
//     unmount, and the browser is free to reclaim the images. Memory tracks what is on screen
//     instead of what has been on screen;
//   - a fetch for a page that has scrolled away is aborted rather than run to completion, so
//     flinging through the collection doesn't queue up work for rows nobody will see.
import { useCallback, useEffect, useRef, useState } from "react";
import { useAuth } from "../lib/keycloak-auth";
import type { Media, MediaTimeline } from "../lib/api-client";
import { timelineAnchors } from "../lib/mosaic-timeline";

// Resident page budget. A viewport plus overscan is a handful of pages even at the smallest tile
// size, so this leaves generous slack for scroll-back while staying bounded.
const MAX_RESIDENT_PAGES = 20;
// Pages to keep loaded either side of the visible range, so ordinary scrolling lands on tiles
// that are already there.
const PREFETCH_PAGES = 1;
const MAX_PARALLEL_FETCHES = 3;
// Rows appear as they stream in rather than a page at a time.
const PARTIAL_COMMIT_EVERY = 20;

export interface MosaicPages {
  /** The media at an absolute offset, or undefined while its page is missing or in flight. */
  mediaAt: (offset: number) => Media | undefined;
  /** Declare the offset range the view needs; drives fetching, prefetch and eviction. */
  requestRange: (firstOffset: number, lastOffset: number) => void;
  /** Bumped whenever resident content changes — the render trigger. */
  revision: number;
  loading: boolean;
}

export function useMosaicPages(timeline: MediaTimeline | undefined): MosaicPages {
  const { api } = useAuth();
  const pagesRef = useRef(new Map<number, Media[]>());
  const pendingRef = useRef(new Map<number, AbortController>());
  const queueRef = useRef<number[]>([]);
  // Page indexes in least-recently-wanted order; the eviction order.
  const usageRef = useRef<number[]>([]);
  const timelineRef = useRef<MediaTimeline | undefined>(undefined);
  const [revision, setRevision] = useState(0);
  const [loading, setLoading] = useState(false);

  const bump = useCallback(() => setRevision((value) => value + 1), []);

  // A new timeline means offsets may refer to different medias (a media was added or removed, so
  // everything after it shifted). Nothing cached can be trusted against the new numbering.
  const reset = useCallback(() => {
    for (const controller of pendingRef.current.values()) controller.abort();
    pendingRef.current.clear();
    pagesRef.current.clear();
    queueRef.current = [];
    usageRef.current = [];
    setLoading(false);
    bump();
  }, [bump]);

  useEffect(() => {
    if (timelineRef.current === timeline) return;
    timelineRef.current = timeline;
    reset();
  }, [timeline, reset]);

  // Drop everything on unmount too, so leaving the tab doesn't leave a fetch running against a
  // component that will never render its result.
  useEffect(() => {
    return () => {
      for (const controller of pendingRef.current.values()) controller.abort();
      pendingRef.current.clear();
      pagesRef.current.clear();
    };
  }, []);

  const pump = useCallback(() => {
    const timelineNow = timelineRef.current;
    if (!timelineNow) return;
    const anchors = timelineAnchors(timelineNow);
    while (pendingRef.current.size < MAX_PARALLEL_FETCHES && queueRef.current.length > 0) {
      const page = queueRef.current.shift()!;
      if (pagesRef.current.has(page) || pendingRef.current.has(page)) continue;
      const anchor = anchors[page];
      if (!anchor) continue;
      const controller = new AbortController();
      pendingRef.current.set(page, controller);
      setLoading(true);
      const items: Media[] = [];
      api
        .mediasStreamFromKey(anchor.accessKey, {
          backward: true,
          inclusive: true,
          limit: timelineNow.step,
          signal: controller.signal,
          onItem: (media) => {
            items.push(media);
            if (items.length % PARTIAL_COMMIT_EVERY === 0 && timelineRef.current === timelineNow) {
              pagesRef.current.set(page, items.slice());
              bump();
            }
          },
        })
        .then(() => {
          if (timelineRef.current !== timelineNow || controller.signal.aborted) return;
          pagesRef.current.set(page, items);
          bump();
        })
        .catch((error) => {
          if (controller.signal.aborted) return;
          // A failed page stays absent, so its tiles keep their placeholder and the next range
          // request retries it rather than caching a hole.
          console.warn(`mosaic: failed to load page ${page}`, error);
        })
        .finally(() => {
          // An aborted fetch may have committed a partial page; that page is incomplete, so drop
          // it rather than leave gaps that would never be filled in.
          if (controller.signal.aborted) pagesRef.current.delete(page);
          if (pendingRef.current.get(page) === controller) pendingRef.current.delete(page);
          setLoading(pendingRef.current.size > 0);
          pump();
        });
    }
  }, [api, bump]);

  const requestRange = useCallback(
    (firstOffset: number, lastOffset: number) => {
      const timelineNow = timelineRef.current;
      if (!timelineNow || timelineNow.total === 0) return;
      const step = Math.max(1, timelineNow.step);
      const anchors = timelineAnchors(timelineNow);
      const lastPage = Math.max(0, anchors.length - 1);
      const firstWanted = Math.max(0, Math.floor(firstOffset / step) - PREFETCH_PAGES);
      const lastWanted = Math.min(lastPage, Math.floor(lastOffset / step) + PREFETCH_PAGES);
      if (lastWanted < firstWanted) return;

      const wanted: number[] = [];
      for (let page = firstWanted; page <= lastWanted; page++) wanted.push(page);
      const wantedSet = new Set(wanted);

      // Abandon work for pages that scrolled out of reach.
      for (const [page, controller] of pendingRef.current) {
        if (!wantedSet.has(page)) controller.abort();
      }
      queueRef.current = queueRef.current.filter((page) => wantedSet.has(page));

      // Refresh usage order: wanted pages become the most recent, ordered outward from the middle
      // of the view so eviction sheds the far edges first.
      const middle = (firstWanted + lastWanted) / 2;
      const byDistance = [...wanted].sort((a, b) => Math.abs(b - middle) - Math.abs(a - middle));
      usageRef.current = usageRef.current.filter((page) => !wantedSet.has(page)).concat(byDistance);

      // Evict beyond the budget, least recently wanted first, never touching what is on screen.
      let resident = pagesRef.current.size;
      for (const page of usageRef.current) {
        if (resident <= MAX_RESIDENT_PAGES) break;
        if (wantedSet.has(page)) continue;
        if (pagesRef.current.delete(page)) resident--;
      }
      usageRef.current = usageRef.current.filter((page) => pagesRef.current.has(page) || wantedSet.has(page));

      // Queue what's missing, nearest the middle of the view first.
      const missing = wanted.filter((page) => !pagesRef.current.has(page) && !pendingRef.current.has(page));
      missing.sort((a, b) => Math.abs(a - middle) - Math.abs(b - middle));
      queueRef.current = missing.concat(queueRef.current.filter((page) => !missing.includes(page)));
      pump();
    },
    [pump]
  );

  const mediaAt = useCallback((offset: number) => {
    const timelineNow = timelineRef.current;
    if (!timelineNow) return undefined;
    const step = Math.max(1, timelineNow.step);
    const page = pagesRef.current.get(Math.floor(offset / step));
    return page?.[offset % step];
  }, []);

  return { mediaAt, requestRange, revision, loading };
}
