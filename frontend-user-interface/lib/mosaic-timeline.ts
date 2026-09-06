// Lookups over the mosaic seek table (GET /api/medias/timeline): a newest-first list of
// `{ offset, accessKey, timestamp }` anchors, one every `step` medias, plus the exact total.
//
// This replaces the old logarithmic scroll<->timestamp curve, which guessed where a date sat in
// the collection from nothing but its oldest and newest bounds. Guessing is why the rail and the
// scrollbar used to disagree: the rail placed 2019 by elapsed time, the scrollbar placed it by
// however many tiles happened to be loaded. Anchors give the real answer — offset 41 300 *is*
// March 2019 — so both surfaces can be driven from the same number.
import type { MediaTimeline, MediaTimelineAnchor } from "./api-client";

export type { MediaTimeline, MediaTimelineAnchor };

export function timelineAnchors(timeline: MediaTimeline | undefined): MediaTimelineAnchor[] {
  return timeline?.anchors ?? [];
}

/** Index of the last anchor at or before `offset` (anchors are ascending in offset). */
function anchorIndexForOffset(anchors: MediaTimelineAnchor[], offset: number): number {
  let low = 0;
  let high = anchors.length - 1;
  let found = 0;
  while (low <= high) {
    const mid = (low + high) >> 1;
    if (anchors[mid].offset <= offset) {
      found = mid;
      low = mid + 1;
    } else {
      high = mid - 1;
    }
  }
  return found;
}

/**
 * Approximate shoot date of the media at `offset`, linearly interpolated between the surrounding
 * anchors. Exact at anchor offsets, and never off by more than one anchor gap in between — good
 * enough for a scroll indicator and rail tooltip, which is all it drives.
 */
export function dateAtOffset(timeline: MediaTimeline | undefined, offset: number): Date | null {
  const anchors = timelineAnchors(timeline);
  if (anchors.length === 0) return null;
  const index = anchorIndexForOffset(anchors, offset);
  const anchor = anchors[index];
  const next = anchors[index + 1];
  const start = new Date(anchor.timestamp).getTime();
  if (!next || next.offset === anchor.offset) return new Date(start);
  const end = new Date(next.timestamp).getTime();
  const progress = Math.max(0, Math.min(1, (offset - anchor.offset) / (next.offset - anchor.offset)));
  return new Date(start + (end - start) * progress);
}

/**
 * Offset of the first media at or older than `timestamp` — how an inbound `/mosaic?ts=...` link
 * (from the Viewer's date button, or a Bag's date) becomes a scroll position. Anchors run
 * newest-first, so their timestamps are descending.
 */
export function offsetAtDate(timeline: MediaTimeline | undefined, timestamp: string): number | null {
  const anchors = timelineAnchors(timeline);
  if (anchors.length === 0) return null;
  const target = new Date(timestamp).getTime();
  if (!Number.isFinite(target)) return null;
  let low = 0;
  let high = anchors.length - 1;
  let found = 0;
  while (low <= high) {
    const mid = (low + high) >> 1;
    if (new Date(anchors[mid].timestamp).getTime() >= target) {
      found = mid;
      low = mid + 1;
    } else {
      high = mid - 1;
    }
  }
  return anchors[found].offset;
}

export interface TimelineYearMark {
  year: number;
  /** Offset at which this year first appears, scanning newest-first. */
  offset: number;
}

/**
 * Year boundaries positioned by media *count*, not by elapsed time: a year you shot 8 000 photos
 * in gets eight times the rail space of one you shot 1 000 in, and the mark lines up with where
 * the scrollbar actually puts that year. Resolution is one anchor (`timeline.step` medias), which
 * on a rail a few hundred pixels tall is well under a pixel.
 */
export function timelineYearMarks(timeline: MediaTimeline | undefined): TimelineYearMark[] {
  const anchors = timelineAnchors(timeline);
  const marks: TimelineYearMark[] = [];
  let previousYear: number | null = null;
  for (const anchor of anchors) {
    const year = new Date(anchor.timestamp).getUTCFullYear();
    if (!Number.isFinite(year)) continue;
    if (previousYear === null || year !== previousYear) {
      marks.push({ year, offset: anchor.offset });
      previousYear = year;
    }
  }
  return marks;
}
