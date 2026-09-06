"use client";

// The date rail down the left of the mosaic.
//
// Every position on it is a fraction of the collection by media count, which is the same thing
// the scrollbar measures — so the rail's cursor and the scrollbar thumb always sit at the same
// height, and dropping the cursor on "2019" lands on 2019. Years are spaced by how many photos
// they hold rather than by elapsed time, so a heavily shot year is a tall band and a sparse one a
// thin line, which is also what makes them easy to hit.
//
// Dragging scrubs continuously (pointer capture, so the drag survives leaving the rail), and the
// tooltip reads the date out of the seek table as you go.
import { useCallback, useMemo, useRef, useState } from "react";
import type { MediaTimeline } from "../lib/api-client";
import { dateAtOffset, timelineYearMarks } from "../lib/mosaic-timeline";

// Minimum gap between two year labels before the later one is drawn as a tick only.
const LABEL_MIN_RATIO_GAP = 0.035;

export function MosaicTimelineRail({
  timeline,
  cursorRatio,
  onSeekRatio,
}: {
  timeline: MediaTimeline | undefined;
  cursorRatio: number;
  onSeekRatio: (ratio: number) => void;
}) {
  const railRef = useRef<HTMLDivElement>(null);
  const [hover, setHover] = useState<{ ratio: number; text: string } | null>(null);
  const total = timeline?.total ?? 0;

  const marks = useMemo(() => {
    if (!timeline || total === 0) return [];
    let lastLabelRatio = -1;
    return timelineYearMarks(timeline).map(({ year, offset }) => {
      const ratio = Math.max(0, Math.min(1, offset / total));
      const labelled = lastLabelRatio < 0 || ratio - lastLabelRatio >= LABEL_MIN_RATIO_GAP;
      if (labelled) lastLabelRatio = ratio;
      return { year, ratio, labelled };
    });
  }, [timeline, total]);

  const ratioAt = useCallback((clientY: number) => {
    const rail = railRef.current;
    if (!rail) return 0;
    const rect = rail.getBoundingClientRect();
    return Math.max(0, Math.min(1, (clientY - rect.top) / Math.max(1, rect.height)));
  }, []);

  const describe = useCallback(
    (ratio: number) => {
      const date = dateAtOffset(timeline, ratio * Math.max(0, total - 1));
      return date ? date.toLocaleDateString(undefined, { year: "numeric", month: "long" }) : "";
    },
    [timeline, total]
  );

  const scrubbing = useRef(false);

  function onPointerDown(event: React.PointerEvent<HTMLDivElement>) {
    if (total === 0) return;
    scrubbing.current = true;
    event.currentTarget.setPointerCapture(event.pointerId);
    onSeekRatio(ratioAt(event.clientY));
  }

  function onPointerMove(event: React.PointerEvent<HTMLDivElement>) {
    if (total === 0) return;
    const ratio = ratioAt(event.clientY);
    setHover({ ratio, text: describe(ratio) });
    if (scrubbing.current) onSeekRatio(ratio);
  }

  function endScrub(event: React.PointerEvent<HTMLDivElement>) {
    if (!scrubbing.current) return;
    scrubbing.current = false;
    if (event.currentTarget.hasPointerCapture(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId);
  }

  return (
    <div
      ref={railRef}
      className="mosaic-timeline"
      aria-label="Timeline — drag to move through the collection"
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={endScrub}
      onPointerCancel={endScrub}
      onMouseLeave={() => setHover(null)}
    >
      {marks.map(({ year, ratio, labelled }) => (
        // Labels are centred on their line, so the first and last would be half cut off by the
        // rail's own edges; nudge those two inside instead.
        <div key={`${year}-${ratio}`} className={`year-mark${labelled ? " labelled" : ""}${ratio < 0.01 ? " at-start" : ratio > 0.99 ? " at-end" : ""}`} style={{ top: `${ratio * 100}%` }}>
          <div className="year-line" />
          {labelled && <div className="year-label">{year}</div>}
        </div>
      ))}
      <div className="cursor" style={{ top: `${cursorRatio * 100}%` }} />
      {hover && hover.text && (
        <div className="tooltip show" style={{ top: `${hover.ratio * 100}%` }}>
          {hover.text}
        </div>
      )}
    </div>
  );
}
