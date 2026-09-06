// Grid geometry for the virtualized mosaic.
//
// The mosaic renders a fixed, square-tile grid over a media collection whose exact size is known
// up front (GET /api/medias/timeline), so every position in it is computable rather than
// discovered by scrolling: item `offset` lives on row `floor(offset / columns)`, and the full
// content height is `rows * rowHeight`. That is what lets the scroll container be sized to the
// whole collection — a scrollbar whose thumb means "you are 60% of the way through your photos" —
// while only the rows actually on screen are mounted.
//
// Kept free of React and the DOM (callers pass a measured width) so the arithmetic that decides
// what is on screen can be reasoned about, and tested, on its own.

export type MosaicSizeKey = "small" | "medium" | "large";

export const MOSAIC_SIZES: MosaicSizeKey[] = ["small", "medium", "large"];

// Minimum tile edge per size; the real edge grows to divide the available width evenly, exactly
// like the CSS `repeat(auto-fill, minmax(N, 1fr))` this replaces.
const TILE_MINIMUM: Record<MosaicSizeKey, number> = { small: 90, medium: 140, large: 200 };
const TILE_GAP: Record<MosaicSizeKey, number> = { small: 4, medium: 6, large: 8 };

export interface MosaicLayout {
  size: MosaicSizeKey;
  columns: number;
  /** Tile edge in px (tiles are square). */
  tile: number;
  gap: number;
  /** Distance from one row's top to the next: tile + gap. */
  rowHeight: number;
  rows: number;
  /** Full scrollable height for the whole collection, trailing gap trimmed. */
  contentHeight: number;
  total: number;
}

export function computeMosaicLayout(width: number, total: number, size: MosaicSizeKey): MosaicLayout {
  const gap = TILE_GAP[size];
  const minimum = TILE_MINIMUM[size];
  const usable = Math.max(0, width);
  const columns = Math.max(1, Math.floor((usable + gap) / (minimum + gap)));
  const tile = Math.max(1, Math.floor((usable - (columns - 1) * gap) / columns));
  const rowHeight = tile + gap;
  const rows = Math.ceil(Math.max(0, total) / columns);
  return {
    size,
    columns,
    tile,
    gap,
    rowHeight,
    rows,
    contentHeight: rows > 0 ? rows * rowHeight - gap : 0,
    total: Math.max(0, total),
  };
}

/** Inclusive row range covering the viewport, padded by `overscanRows` on each side. */
export function visibleRows(scrollTop: number, viewportHeight: number, layout: MosaicLayout, overscanRows: number): { firstRow: number; lastRow: number } {
  if (layout.rows === 0) return { firstRow: 0, lastRow: -1 };
  const first = Math.floor(scrollTop / layout.rowHeight) - overscanRows;
  const last = Math.floor((scrollTop + Math.max(0, viewportHeight) - 1) / layout.rowHeight) + overscanRows;
  return {
    firstRow: Math.max(0, Math.min(layout.rows - 1, first)),
    lastRow: Math.max(0, Math.min(layout.rows - 1, last)),
  };
}

/** Scroll offset that puts `offset`'s row at the top of the viewport. */
export function offsetToScrollTop(offset: number, layout: MosaicLayout): number {
  const row = Math.floor(clampOffset(offset, layout) / layout.columns);
  return row * layout.rowHeight;
}

/** First item offset visible at `scrollTop` — the inverse of offsetToScrollTop, rounded down. */
export function scrollTopToOffset(scrollTop: number, layout: MosaicLayout): number {
  const row = Math.max(0, Math.floor(Math.max(0, scrollTop) / layout.rowHeight));
  return clampOffset(row * layout.columns, layout);
}

export function clampOffset(offset: number, layout: MosaicLayout): number {
  if (layout.total === 0) return 0;
  return Math.max(0, Math.min(layout.total - 1, Math.round(offset)));
}

/**
 * Fraction of the collection scrolled past, expressed the same way the native scrollbar thumb
 * expresses it: 0 at the very top, 1 only when the last row is fully in view. Using the same
 * definition for the timeline rail's cursor is what keeps rail and scrollbar in agreement.
 */
export function scrollRatio(scrollTop: number, viewportHeight: number, layout: MosaicLayout): number {
  const scrollable = layout.contentHeight - viewportHeight;
  if (scrollable <= 0) return 0;
  return Math.max(0, Math.min(1, scrollTop / scrollable));
}

/** Inverse of scrollRatio: where to scroll so the rail cursor lands at `ratio`. */
export function scrollTopForRatio(ratio: number, viewportHeight: number, layout: MosaicLayout): number {
  const scrollable = Math.max(0, layout.contentHeight - viewportHeight);
  return Math.max(0, Math.min(scrollable, ratio * scrollable));
}
