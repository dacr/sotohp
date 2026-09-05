"use client";

// Drag-to-move / drag-corner-to-resize crop region editor for a portfolio asset. All geometry is
// fractional (0..1 relative to the displayed image), matching the BoundingBox the API stores.
import { useEffect, useRef, useState } from "react";
import type { BoundingBox } from "../lib/api-client";

function clamp(v: number, min: number, max: number) {
  return Math.max(min, Math.min(max, v));
}

const HANDLES: [string, number, number, string][] = [
  ["nw", 0, 0, "nwse"],
  ["ne", 1, 0, "nesw"],
  ["sw", 0, 1, "nesw"],
  ["se", 1, 1, "nwse"],
];

export function CropEditor({ imgSrc, box, onChange }: { imgSrc: string; box: BoundingBox | null; onChange: (b: BoundingBox | null) => void }) {
  const stageRef = useRef<HTMLDivElement>(null);
  const imgRef = useRef<HTMLImageElement>(null);
  const boxRef = useRef(box);
  boxRef.current = box;
  const [loaded, setLoaded] = useState(false);
  const [drawMode, setDrawMode] = useState(false);
  const [, bumpLayout] = useState(0); // re-measure the overlay's on-screen rect (image size can change on load/resize)

  useEffect(() => {
    const onResize = () => bumpLayout((n) => n + 1);
    window.addEventListener("resize", onResize);
    return () => window.removeEventListener("resize", onResize);
  }, []);
  useEffect(() => bumpLayout((n) => n + 1), [box]);

  function overlayRect() {
    const img = imgRef.current;
    const stage = stageRef.current;
    if (!img || !stage || !box) return null;
    const rect = img.getBoundingClientRect();
    const stageRect = stage.getBoundingClientRect();
    return { left: rect.left - stageRect.left + box.x * rect.width, top: rect.top - stageRect.top + box.y * rect.height, width: box.width * rect.width, height: box.height * rect.height };
  }

  function onMoveStart(e: React.PointerEvent) {
    const img = imgRef.current;
    const orig = boxRef.current;
    if (!img || !orig) return;
    e.preventDefault();
    e.stopPropagation();
    const rect = img.getBoundingClientRect();
    const startX = e.clientX;
    const startY = e.clientY;
    function onMove(ev: PointerEvent) {
      const dx = (ev.clientX - startX) / rect.width;
      const dy = (ev.clientY - startY) / rect.height;
      onChange({ x: clamp(orig!.x + dx, 0, 1 - orig!.width), y: clamp(orig!.y + dy, 0, 1 - orig!.height), width: orig!.width, height: orig!.height });
    }
    function onUp() {
      window.removeEventListener("pointermove", onMove);
      window.removeEventListener("pointerup", onUp);
    }
    window.addEventListener("pointermove", onMove);
    window.addEventListener("pointerup", onUp);
  }

  function onResizeStart(which: string) {
    return (e: React.PointerEvent) => {
      const img = imgRef.current;
      const orig = boxRef.current;
      if (!img || !orig) return;
      e.preventDefault();
      e.stopPropagation();
      const rect = img.getBoundingClientRect();
      const startX = e.clientX;
      const startY = e.clientY;
      const MIN = 0.02;
      function onMove(ev: PointerEvent) {
        const dx = (ev.clientX - startX) / rect.width;
        const dy = (ev.clientY - startY) / rect.height;
        let { x, y, width: w, height: h } = orig!;
        if (which.includes("w")) {
          const nx = clamp(orig!.x + dx, 0, orig!.x + orig!.width - MIN);
          w = orig!.width + (orig!.x - nx);
          x = nx;
        }
        if (which.includes("e")) w = clamp(orig!.width + dx, MIN, 1 - orig!.x);
        if (which.includes("n")) {
          const ny = clamp(orig!.y + dy, 0, orig!.y + orig!.height - MIN);
          h = orig!.height + (orig!.y - ny);
          y = ny;
        }
        if (which.includes("s")) h = clamp(orig!.height + dy, MIN, 1 - orig!.y);
        onChange({ x, y, width: w, height: h });
      }
      function onUp() {
        window.removeEventListener("pointermove", onMove);
        window.removeEventListener("pointerup", onUp);
      }
      window.addEventListener("pointermove", onMove);
      window.addEventListener("pointerup", onUp);
    };
  }

  function startDraw(e: React.PointerEvent) {
    if (!drawMode) return;
    const img = imgRef.current;
    if (!img) return;
    e.preventDefault();
    const rect = img.getBoundingClientRect();
    const startXRel = clamp((e.clientX - rect.left) / rect.width, 0, 1);
    const startYRel = clamp((e.clientY - rect.top) / rect.height, 0, 1);
    function onMove(ev: PointerEvent) {
      const xr = clamp((ev.clientX - rect.left) / rect.width, 0, 1);
      const yr = clamp((ev.clientY - rect.top) / rect.height, 0, 1);
      const width = Math.abs(xr - startXRel);
      const height = Math.abs(yr - startYRel);
      if (width >= 0.005 && height >= 0.005) onChange({ x: Math.min(startXRel, xr), y: Math.min(startYRel, yr), width, height });
    }
    function onUp() {
      window.removeEventListener("pointermove", onMove);
      window.removeEventListener("pointerup", onUp);
      setDrawMode(false);
    }
    window.addEventListener("pointermove", onMove);
    window.addEventListener("pointerup", onUp);
  }

  const rect = overlayRect();

  return (
    <div>
      <div style={{ display: "flex", gap: 8, margin: "4px 0", alignItems: "center" }}>
        <button type="button" className={`btn btn-soft btn-sm${drawMode ? " is-active" : ""}`} onClick={() => setDrawMode((d) => !d)}>
          {drawMode ? "× Cancel draw" : "＋ Draw new crop"}
        </button>
        <button type="button" className="btn btn-danger-soft btn-sm" disabled={!box} onClick={() => onChange(null)}>
          🗑 Remove crop
        </button>
        <span style={{ fontSize: 11, color: "#6b7280" }}>{box ? `x: ${box.x.toFixed(3)}, y: ${box.y.toFixed(3)}, w: ${box.width.toFixed(3)}, h: ${box.height.toFixed(3)}` : "No crop — full image"}</span>
      </div>
      <div
        ref={stageRef}
        onPointerDown={startDraw}
        style={{ position: "relative", width: "100%", background: "#0f172a", borderRadius: 6, overflow: "hidden", display: "flex", alignItems: "center", justifyContent: "center", minHeight: 200, cursor: drawMode ? "crosshair" : "default" }}
      >
        {!loaded && <span style={{ color: "#9ca3af", fontSize: 13 }}>Loading image…</span>}
        <img
          ref={imgRef}
          src={imgSrc}
          onLoad={() => {
            setLoaded(true);
            bumpLayout((n) => n + 1);
          }}
          draggable={false}
          style={{ display: loaded ? "block" : "none", maxWidth: "100%", maxHeight: "50vh", width: "auto", height: "auto", userSelect: "none" }}
          alt=""
        />
        {rect && (
          <div onPointerDown={onMoveStart} style={{ position: "absolute", left: rect.left, top: rect.top, width: rect.width, height: rect.height, border: "2px solid #2563eb", boxShadow: "0 0 0 9999px rgba(0,0,0,0.45)", cursor: "move" }}>
            {HANDLES.map(([key, fx, fy, cur]) => (
              <div
                key={key}
                onPointerDown={onResizeStart(key)}
                style={{ position: "absolute", left: `${fx * 100}%`, top: `${fy * 100}%`, width: 12, height: 12, marginLeft: -6, marginTop: -6, background: "#fff", border: "2px solid #2563eb", borderRadius: 2, cursor: `${cur}-resize` }}
              />
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
