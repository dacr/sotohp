"use client";

// Mosaic tab — a virtualized grid over the whole media collection.
//
// The collection's exact size is known up front (hooks/useMosaicTimeline), so the scroll container
// is sized to all of it and only the rows on screen are mounted. Three things follow, and they are
// the point of the design:
//
//   - the scrollbar is real. Its thumb is proportional to the whole collection and dragging it to
//     the middle lands in the middle, instead of describing however much had happened to load;
//   - the timeline rail, the scrollbar and the position indicator are all driven by the same
//     offset, so they cannot disagree with each other;
//   - what is off screen is unmounted and its pages evicted (hooks/useMosaicPages), so scrolling
//     for an hour costs what scrolling for a minute costs.
//
// Position is expressed as an *offset* (the index of the first visible media) rather than a pixel
// scrollTop, because that is the one quantity that survives a resize, a tile-size change and a
// remount — all of which used to scramble where you were.
import { useSearchParams } from "next/navigation";
import { Suspense, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { MosaicTile } from "../../components/MosaicTile";
import { MosaicTimelineRail } from "../../components/MosaicTimelineRail";
import { useMosaicPages } from "../../hooks/useMosaicPages";
import { useMosaicTimeline } from "../../hooks/useMosaicTimeline";
import { computeMosaicLayout, offsetToScrollTop, scrollRatio, scrollTopForRatio, scrollTopToOffset, visibleRows, MOSAIC_SIZES, type MosaicSizeKey } from "../../lib/mosaic-geometry";
import { useAuth } from "../../lib/keycloak-auth";
import { mediaTimestamp } from "../../lib/media-timestamp";
import { dateAtOffset, offsetAtDate, timelineAnchors } from "../../lib/mosaic-timeline";
import type { Media } from "../../lib/api-client";

const SIZE_KEY = "mosaic.size";
const OFFSET_KEY = "sotohp:mosaic:offset";
// Rows rendered beyond the viewport on each side. Enough that a normal scroll never exposes an
// empty row, small enough that the mounted tile count stays a couple of screenfuls.
const OVERSCAN_ROWS = 3;
const INDICATOR_LINGER_MS = 900;
// How long the photo a `?ts=` seek landed on stays ringed.
const HIGHLIGHT_MS = 2600;

export default function MosaicPage() {
  return (
    <Suspense fallback={<section className="mosaic-page" />}>
      <MosaicPageInner />
    </Suspense>
  );
}

function MosaicPageInner() {
  const searchParams = useSearchParams();
  const requestedTimestamp = searchParams.get("ts");
  // The Viewer knows exactly which photo you were looking at, so it sends the key alongside the
  // date. Two photos can share a timestamp, and only the key tells them apart.
  const requestedMediaKey = searchParams.get("media");

  const viewportRef = useRef<HTMLDivElement>(null);
  const sizerRef = useRef<HTMLDivElement>(null);

  const { api } = useAuth();
  const { data: timeline, isLoading: timelineLoading, isError: timelineError } = useMosaicTimeline();
  const { mediaAt, requestRange, revision, loading } = useMosaicPages(timeline);

  const [size, setSize] = useState<MosaicSizeKey>("large");
  const [viewport, setViewport] = useState({ width: 0, height: 0 });
  const [scrollTop, setScrollTop] = useState(0);
  const [indicating, setIndicating] = useState(false);

  const total = timeline?.total ?? 0;
  const layout = useMemo(() => computeMosaicLayout(viewport.width, total, size), [viewport.width, total, size]);

  // The offset the viewer is looking at. Kept in a ref as well as state: the ref is what layout
  // changes read to put you back where you were, and it must be up to date at the moment the
  // layout effect runs, before React has re-rendered.
  const [topOffset, setTopOffset] = useState(0);
  const topOffsetRef = useRef(0);
  const restoredRef = useRef(false);
  // Separate from restoredRef, which is set the instant the restore starts to stop it re-running.
  // This one only opens once the restored scroll has been reflected back into state, so the
  // derived "you are at offset 0" of the render before it can never be written over the position
  // we are in the middle of restoring.
  const persistEnabledRef = useRef(false);

  // A `?ts=` seek resolves in two phases. The seek table only locates a date to within one page
  // (anchors are `timeline.step` medias apart), which is enough to fetch the right page in one
  // request but lands you up to 99 photos short of the one you asked for — near enough to look
  // right, wrong enough to be useless. Phase two runs once that page's medias arrive and refines
  // to the exact photo, then highlights it so it is obvious which one was meant.
  const [pendingSeek, setPendingSeek] = useState<{ timestamp: string; mediaKey: string | null; pageStart: number } | null>(null);
  const [highlightOffset, setHighlightOffset] = useState<number | null>(null);
  const highlightTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    try {
      const saved = localStorage.getItem(SIZE_KEY);
      // "max" was the old name for the largest tile size.
      if (saved === "max") setSize("large");
      else if (saved && MOSAIC_SIZES.includes(saved as MosaicSizeKey)) setSize(saved as MosaicSizeKey);
    } catch {
      /* ignore */
    }
  }, []);

  function persistSize(next: MosaicSizeKey) {
    setSize(next);
    try {
      localStorage.setItem(SIZE_KEY, next);
    } catch {
      /* ignore */
    }
  }

  // --- Measurement -----------------------------------------------------------------------------
  // The sizer spans the scroll container's content box, so its width is the width the grid gets
  // — already free of padding and of the scrollbar's own gutter.
  useLayoutEffect(() => {
    const sizer = sizerRef.current;
    const view = viewportRef.current;
    if (!sizer || !view) return;
    function measure() {
      const node = sizerRef.current;
      const port = viewportRef.current;
      if (!node || !port) return;
      setViewport((prev) => {
        const width = node.clientWidth;
        const height = port.clientHeight;
        return prev.width === width && prev.height === height ? prev : { width, height };
      });
    }
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(sizer);
    observer.observe(view);
    return () => observer.disconnect();
  }, []);

  // --- Scrolling -------------------------------------------------------------------------------
  const indicatorTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const persistTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    const view = viewportRef.current;
    if (!view) return;
    let scheduled = false;
    function onScroll() {
      if (scheduled) return;
      scheduled = true;
      requestAnimationFrame(() => {
        scheduled = false;
        const node = viewportRef.current;
        if (!node) return;
        setScrollTop(node.scrollTop);
        setIndicating(true);
        if (indicatorTimer.current) clearTimeout(indicatorTimer.current);
        indicatorTimer.current = setTimeout(() => setIndicating(false), INDICATOR_LINGER_MS);
      });
    }
    view.addEventListener("scroll", onScroll, { passive: true });
    return () => {
      view.removeEventListener("scroll", onScroll);
      if (indicatorTimer.current) clearTimeout(indicatorTimer.current);
    };
  }, []);

  // Derive the top offset from wherever the scroll ended up, and remember it for the next visit.
  useEffect(() => {
    if (layout.total === 0) return;
    const offset = scrollTopToOffset(scrollTop, layout);
    topOffsetRef.current = offset;
    setTopOffset(offset);
    if (!persistEnabledRef.current) return;
    if (persistTimer.current) clearTimeout(persistTimer.current);
    persistTimer.current = setTimeout(() => {
      try {
        sessionStorage.setItem(OFFSET_KEY, String(offset));
      } catch {
        /* ignore */
      }
    }, 300);
  }, [scrollTop, layout]);

  useEffect(() => () => { if (persistTimer.current) clearTimeout(persistTimer.current); }, []);

  // A focused scroll container gets Home / End / PageUp / PageDown / arrow scrolling from the
  // browser, which is the whole keyboard story for a grid like this — but only once it has focus,
  // and every other way of giving it focus (clicking a tile) navigates away instead. Take it on
  // mount, without the scroll-into-view focus() would otherwise do.
  useEffect(() => {
    const view = viewportRef.current;
    if (!view) return;
    if (document.activeElement === document.body || document.activeElement === null) view.focus({ preventScroll: true });
  }, []);

  const scrollToOffset = useCallback(
    (offset: number, behavior: ScrollBehavior = "auto") => {
      const view = viewportRef.current;
      if (!view || layout.total === 0) return;
      const target = offsetToScrollTop(offset, layout);
      topOffsetRef.current = offset;
      view.scrollTo({ top: target, behavior });
      // scrollTo doesn't fire a scroll event when the position is unchanged, so mirror it here
      // rather than let the derived state go stale.
      setScrollTop(target);
    },
    [layout]
  );

  // --- Initial position ------------------------------------------------------------------------
  // Restored once, as soon as there is both a collection and a measured layout to place it in:
  // an inbound `?ts=` link (from the Viewer's date button or a Bag) wins, otherwise the offset
  // this tab was last left at.
  useLayoutEffect(() => {
    if (restoredRef.current || layout.total === 0 || layout.columns === 0 || viewport.width === 0) return;
    restoredRef.current = true;
    let target = 0;
    if (requestedTimestamp) {
      const fromDate = offsetAtDate(timeline, requestedTimestamp);
      if (fromDate !== null) {
        target = fromDate;
        // fromDate is an anchor offset, so it is exactly the start of the page holding the date.
        setPendingSeek({ timestamp: requestedTimestamp, mediaKey: requestedMediaKey, pageStart: fromDate });
      }
      // The `?ts=`/`?media=` params deliberately stay in the URL: rewriting them away from here
      // re-renders the route mid-restore, which remounts this component and throws away the
      // pending seek before it can refine. NavHeader is what needs to ignore them, and does -
      // see its ONE_SHOT_PARAMS - so returning to this tab still restores where you left it.
    } else {
      try {
        const saved = sessionStorage.getItem(OFFSET_KEY);
        const parsed = saved ? parseInt(saved, 10) : NaN;
        if (Number.isFinite(parsed)) target = parsed;
      } catch {
        /* ignore */
      }
    }
    scrollToOffset(Math.max(0, Math.min(layout.total - 1, target)));
    requestAnimationFrame(() => {
      persistEnabledRef.current = true;
    });
  }, [layout, viewport.width, requestedTimestamp, requestedMediaKey, timeline, scrollToOffset]);

  // Phase two of a `?ts=` seek: find the exact photo inside the page the anchor located.
  //
  // This fetches that page itself rather than waiting for the grid's own cache to hold it. Reading
  // the shared cache would be free, but it only answers once the virtualizer has decided to load
  // that page and finished streaming it — a race that resolved on a fresh page load and silently
  // never resolved on an in-app navigation, leaving the seek stuck on the coarse landing. One
  // extra request, only when a seek was actually asked for, buys a result that does not depend on
  // what the grid happens to be doing.
  useEffect(() => {
    if (!pendingSeek || !timeline || layout.total === 0) return;
    const target = new Date(pendingSeek.timestamp).getTime();
    const anchor = timelineAnchors(timeline)[Math.floor(pendingSeek.pageStart / timeline.step)];
    if (!anchor || !Number.isFinite(target)) {
      setPendingSeek(null);
      return;
    }
    const controller = new AbortController();
    const items: Media[] = [];
    api
      .mediasStreamFromKey(anchor.accessKey, { backward: true, inclusive: true, limit: timeline.step, signal: controller.signal, onItem: (m) => items.push(m) })
      .then(() => {
        if (controller.signal.aborted) return;
        const index = items.findIndex((media) => {
          if (pendingSeek.mediaKey) return media.accessKey === pendingSeek.mediaKey;
          const stamp = mediaTimestamp(media);
          return stamp !== null && new Date(stamp).getTime() <= target;
        });
        setPendingSeek(null);
        // No match means the key names a media that has since moved, or the date sits past the end
        // of the collection; the coarse landing is then the best answer available.
        if (index < 0) return;
        const offset = pendingSeek.pageStart + index;
        scrollToOffset(offset);
        setHighlightOffset(offset);
        if (highlightTimer.current) clearTimeout(highlightTimer.current);
        highlightTimer.current = setTimeout(() => setHighlightOffset(null), HIGHLIGHT_MS);
      })
      .catch((error) => {
        if (controller.signal.aborted) return;
        console.warn("mosaic: could not resolve the requested date to a photo", error);
        setPendingSeek(null);
      });
    return () => controller.abort();
  }, [pendingSeek, timeline, layout.total, api, scrollToOffset]);

  useEffect(() => () => { if (highlightTimer.current) clearTimeout(highlightTimer.current); }, []);

  // Column count or row height changed (window resize, tile size switch): hold the viewer's place
  // by offset. Without this, changing tile size threw you an arbitrary distance through the
  // collection, since the same scrollTop means a different photo at a different tile size.
  const geometrySignature = `${layout.columns}x${layout.rowHeight}`;
  const previousGeometry = useRef(geometrySignature);
  useLayoutEffect(() => {
    if (previousGeometry.current === geometrySignature) return;
    previousGeometry.current = geometrySignature;
    if (!restoredRef.current || layout.total === 0) return;
    scrollToOffset(topOffsetRef.current);
  }, [geometrySignature, layout, scrollToOffset]);

  // --- What to render, and what to fetch --------------------------------------------------------
  const { firstRow, lastRow } = visibleRows(scrollTop, viewport.height, layout, OVERSCAN_ROWS);
  const firstOffset = firstRow * layout.columns;
  const lastOffset = Math.min(layout.total - 1, (lastRow + 1) * layout.columns - 1);

  useEffect(() => {
    if (layout.total === 0 || lastOffset < firstOffset) return;
    requestRange(firstOffset, lastOffset);
  }, [firstOffset, lastOffset, layout.total, requestRange]);

  const tiles = useMemo(() => {
    if (layout.total === 0 || lastOffset < firstOffset) return [];
    const out = [];
    for (let offset = firstOffset; offset <= lastOffset; offset++) {
      const media = mediaAt(offset);
      out.push(
        media ? (
          <MosaicTile key={offset} media={media} offset={offset} highlighted={offset === highlightOffset} />
        ) : (
          <div key={offset} className="mosaic-tile placeholder" aria-hidden="true" />
        )
      );
    }
    return out;
    // `revision` is the signal that mediaAt now answers differently for offsets already on screen.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [firstOffset, lastOffset, layout.total, mediaAt, revision, highlightOffset]);

  const cursorRatio = scrollRatio(scrollTop, viewport.height, layout);
  const currentDate = dateAtOffset(timeline, topOffset);

  const onSeekRatio = useCallback(
    (ratio: number) => {
      const view = viewportRef.current;
      if (!view || layout.total === 0) return;
      view.scrollTop = scrollTopForRatio(ratio, viewport.height, layout);
      setScrollTop(view.scrollTop);
      setIndicating(true);
      if (indicatorTimer.current) clearTimeout(indicatorTimer.current);
      indicatorTimer.current = setTimeout(() => setIndicating(false), INDICATOR_LINGER_MS);
    },
    [layout, viewport.height]
  );

  return (
    <section className="mosaic-page">
      <div className={`mosaic-scroll-indicator${indicating && currentDate ? " show" : ""}`}>
        {currentDate ? currentDate.toLocaleDateString(undefined, { year: "numeric", month: "long", day: "numeric" }) : ""}
        <div className="position">{layout.total > 0 ? `${(topOffset + 1).toLocaleString()} / ${layout.total.toLocaleString()}` : ""}</div>
      </div>
      <div className="mosaic-header">
        <span className="mosaic-count">
          {timelineLoading ? "Loading…" : timelineError ? "Timeline unavailable" : `${total.toLocaleString()} photos`}
          {loading && !timelineLoading && <span className="mosaic-busy" aria-label="loading tiles" />}
        </span>
        <span style={{ marginLeft: "auto", marginRight: 8, fontSize: 14, color: "#374151" }}>Size:</span>
        <div className="segmented" role="group" aria-label="Mosaic size">
          {MOSAIC_SIZES.map((candidate) => (
            <button key={candidate} type="button" className={size === candidate ? "active" : ""} onClick={() => persistSize(candidate)}>
              {candidate[0].toUpperCase() + candidate.slice(1)}
            </button>
          ))}
        </div>
      </div>
      <div className="mosaic-body">
        <MosaicTimelineRail timeline={timeline} cursorRatio={cursorRatio} onSeekRatio={onSeekRatio} />
        {/* tabIndex makes the grid a focus target, which is what gives Home / End / PageUp /
            PageDown / arrow scrolling for free from the browser. */}
        <div ref={viewportRef} id="mosaic-container" className={`mosaic-viewport size-${size}`} tabIndex={0}>
          <div ref={sizerRef} className="mosaic-sizer" style={{ height: layout.contentHeight }}>
            <div
              className="mosaic-window"
              style={{
                transform: `translate3d(0, ${firstRow * layout.rowHeight}px, 0)`,
                gridTemplateColumns: `repeat(${layout.columns}, ${layout.tile}px)`,
                gap: layout.gap,
              }}
            >
              {tiles}
            </div>
          </div>
          {!timelineLoading && total === 0 && <div className="mosaic-empty">No photos yet.</div>}
        </div>
      </div>
    </section>
  );
}
