"use client";

import { useSearchParams } from "next/navigation";
import { Suspense, useEffect, useMemo, useRef, useState } from "react";
import { MosaicTile } from "../../components/MosaicTile";
import { useMosaicFeed } from "../../hooks/useMosaicFeed";
import { scrollPositionToTimestamp, timestampToScrollPosition } from "../../lib/mosaic-timeline-math";

const SCROLL_THRESHOLD = 400;
const SIZE_KEY = "mosaic.size";
type SizeKey = "small" | "medium" | "max";

export default function MosaicPage() {
  return (
    <Suspense fallback={<section className="mosaic-page" />}>
      <MosaicPageInner />
    </Suspense>
  );
}

function MosaicPageInner() {
  const searchParams = useSearchParams();
  const initialTs = searchParams.get("ts");
  const containerRef = useRef<HTMLDivElement>(null);
  const gridRef = useRef<HTMLDivElement>(null);
  const timelineRef = useRef<HTMLDivElement>(null);
  const { media, fillerCount, range, indicatorText, cursorRatio, appendOlder, prependNewer, refreshAtTimestamp, updateCursorFromScroll } = useMosaicFeed(containerRef, gridRef, initialTs);
  const [size, setSize] = useState<SizeKey>("max");
  const [hoverTip, setHoverTip] = useState<{ top: number; text: string } | null>(null);

  useEffect(() => {
    try {
      const saved = localStorage.getItem(SIZE_KEY) as SizeKey | null;
      if (saved) setSize(saved);
    } catch {
      /* ignore */
    }
  }, []);
  function persistSize(s: SizeKey) {
    setSize(s);
    try {
      localStorage.setItem(SIZE_KEY, s);
    } catch {
      /* ignore */
    }
  }

  // Any time the tile size changes — from a button click, or from the localStorage restore above
  // right after mount — the already-loaded batch may no longer fill the viewport (max -> small
  // turns few large tiles into many tiny ones with room to spare, and a page revisit always starts
  // from just one batch). Re-check once the new size's layout has actually been painted; appendOlder
  // keeps fetching on its own until the viewport is full or the feed is exhausted.
  useEffect(() => {
    if (media.length === 0) return;
    const id = setTimeout(() => {
      const c = containerRef.current;
      if (c && c.scrollHeight <= c.clientHeight + SCROLL_THRESHOLD) appendOlder();
    }, 80);
    return () => clearTimeout(id);
  }, [size, media.length, appendOlder]);

  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;
    let scheduled = false;
    function onScrollFrame() {
      scheduled = false;
      const c = container!;
      const st = c.scrollTop;
      const sh = c.scrollHeight;
      const ch = c.clientHeight;
      if (st < SCROLL_THRESHOLD) prependNewer();
      else if (st + ch > sh - SCROLL_THRESHOLD) appendOlder();
      updateCursorFromScroll(st, sh, ch);
    }
    function onScroll() {
      if (scheduled) return;
      scheduled = true;
      requestAnimationFrame(onScrollFrame);
    }
    function onWheel(e: WheelEvent) {
      if (container!.scrollTop === 0 && e.deltaY < 0) prependNewer();
    }
    container.addEventListener("scroll", onScroll, { passive: true });
    container.addEventListener("wheel", onWheel, { passive: true });
    return () => {
      container.removeEventListener("scroll", onScroll);
      container.removeEventListener("wheel", onWheel);
    };
  }, [appendOlder, prependNewer, updateCursorFromScroll]);

  const years = useMemo(() => {
    if (!range.oldest || !range.newest) return [];
    const startYear = new Date(range.newest).getUTCFullYear();
    const endYear = new Date(range.oldest).getUTCFullYear();
    const out: { year: number; ratio: number; showLabel: boolean }[] = [];
    let lastLabelRatio = -1;
    for (let y = startYear; y >= endYear; y--) {
      const ts = new Date(Date.UTC(y, 6, 1)).toISOString();
      const ratio = timestampToScrollPosition(ts, range.oldest, range.newest);
      const showLabel = lastLabelRatio < 0 || ratio - lastLabelRatio >= 0.02;
      if (showLabel) lastLabelRatio = ratio;
      out.push({ year: y, ratio, showLabel });
    }
    return out;
  }, [range]);

  function timelineRatioFromEvent(e: React.MouseEvent) {
    const tl = timelineRef.current;
    if (!tl) return 0;
    const rect = tl.getBoundingClientRect();
    return Math.max(0, Math.min(1, (e.clientY - rect.top) / rect.height));
  }

  return (
    <section className="mosaic-page">
      <div className={`mosaic-scroll-indicator${indicatorText ? " show" : ""}`}>{indicatorText}</div>
      <div className="mosaic-header">
        <span style={{ marginRight: 8, fontSize: 14, color: "#374151" }}>Size:</span>
        <div className="segmented" role="group" aria-label="Mosaic size">
          {(["small", "medium", "max"] as SizeKey[]).map((s) => (
            <button key={s} type="button" className={size === s ? "active" : ""} onClick={() => persistSize(s)}>
              {s === "max" ? "Large" : s[0].toUpperCase() + s.slice(1)}
            </button>
          ))}
        </div>
      </div>
      <div className="mosaic-body">
        <div
          ref={timelineRef}
          className="mosaic-timeline"
          aria-label="Timeline"
          title="Click to jump to a time"
          onClick={(e) => {
            if (!range.oldest || !range.newest) return;
            const ts = scrollPositionToTimestamp(timelineRatioFromEvent(e), range.oldest, range.newest);
            if (ts) refreshAtTimestamp(ts);
          }}
          onMouseMove={(e) => {
            if (!range.oldest || !range.newest) return;
            const ratio = timelineRatioFromEvent(e);
            const ts = scrollPositionToTimestamp(ratio, range.oldest, range.newest);
            if (ts) setHoverTip({ top: ratio * (timelineRef.current?.clientHeight || 0), text: new Date(ts).toLocaleDateString() });
          }}
          onMouseLeave={() => setHoverTip(null)}
        >
          <div className="cursor" style={{ top: `${cursorRatio * 100}%` }} />
          {years.map(
            ({ year, ratio, showLabel }) =>
              showLabel && (
                <div key={year} style={{ position: "absolute", top: `${ratio * 100}%`, left: 0, right: 0 }}>
                  <div className="year-line" style={{ top: 0 }} />
                  <div className="year-label" style={{ top: 0 }}>
                    {year}
                  </div>
                </div>
              )
          )}
          {hoverTip && (
            <div className="tooltip show" style={{ top: hoverTip.top }}>
              {hoverTip.text}
            </div>
          )}
        </div>
        <div ref={containerRef} id="mosaic-container" className={`mosaic-container size-${size}`}>
          <div ref={gridRef} className="mosaic-grid">
            {Array.from({ length: fillerCount }).map((_, i) => (
              <div key={`filler-${i}`} className="mosaic-tile-filler" />
            ))}
            {media.map((m) => (
              <MosaicTile key={m.accessKey} media={m} />
            ))}
          </div>
        </div>
      </div>
    </section>
  );
}
