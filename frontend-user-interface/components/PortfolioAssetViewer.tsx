"use client";

// Fullscreen crop- and rotation-aware asset viewer, opened from a portfolio's asset list.
import { useEffect, useRef, useState } from "react";
import { useAuth } from "../lib/keycloak-auth";
import { useMediaAccessKey } from "../hooks/useMediaAccessKey";
import { orientationToDegrees } from "../lib/orientation";
import type { Asset } from "../lib/api-client";

function ViewerStage({ asset, containerSize }: { asset: Asset; containerSize: { w: number; h: number } }) {
  const { api } = useAuth();
  const accessKey = useMediaAccessKey(asset.originalId);
  const [meta, setMeta] = useState<{ rotateDeg: number; natW: number; natH: number } | null>(null);

  useEffect(() => {
    if (!accessKey) return;
    let cancelled = false;
    api
      .getMediaByKey(accessKey)
      .then((m) => {
        if (cancelled) return;
        setMeta({ rotateDeg: orientationToDegrees(m.orientation), natW: m.original.dimension?.width || 1, natH: m.original.dimension?.height || 1 });
      })
      .catch(() => {
        /* handled by the accessKey-missing branch below on failure */
      });
    return () => {
      cancelled = true;
    };
  }, [accessKey, api]);

  if (!accessKey) return <span className="text-danger">Failed to load image</span>;
  if (!meta || !containerSize.w) return <span style={{ color: "#9ca3af" }}>Loading original…</span>;

  const b = asset.selectedBox && asset.selectedBox.width > 0 && asset.selectedBox.height > 0 ? asset.selectedBox : null;
  const cropNatW = b ? meta.natW * b.width : meta.natW;
  const cropNatH = b ? meta.natH * b.height : meta.natH;
  const swapAspect = meta.rotateDeg === 90 || meta.rotateDeg === 270;
  const cropAspect = swapAspect ? cropNatH / cropNatW : cropNatW / cropNatH;
  const stageAspect = containerSize.w / containerSize.h;
  let wrapW: number, wrapH: number;
  if (cropAspect > stageAspect) {
    wrapW = containerSize.w;
    wrapH = containerSize.w / cropAspect;
  } else {
    wrapH = containerSize.h;
    wrapW = containerSize.h * cropAspect;
  }
  const innerW = swapAspect ? wrapH : wrapW;
  const innerH = swapAspect ? wrapW : wrapH;
  const imgStyle = b
    ? { position: "absolute" as const, width: `${(1 / b.width) * 100}%`, height: `${(1 / b.height) * 100}%`, left: `${-(b.x / b.width) * 100}%`, top: `${-(b.y / b.height) * 100}%`, display: "block" }
    : { position: "absolute" as const, inset: 0, width: "100%", height: "100%", display: "block" };

  return (
    <div style={{ position: "relative", width: wrapW, height: wrapH, overflow: "hidden", background: "#000" }}>
      <div
        style={{
          position: "absolute",
          left: (wrapW - innerW) / 2,
          top: (wrapH - innerH) / 2,
          width: innerW,
          height: innerH,
          transformOrigin: "50% 50%",
          transform: `rotate(${meta.rotateDeg}deg)`,
        }}
      >
        <img src={api.mediaOriginalUrl(accessKey)} draggable={false} style={imgStyle} alt="" />
      </div>
    </div>
  );
}

export function PortfolioAssetViewer({ assets, startIndex, onClose }: { assets: Asset[]; startIndex: number; onClose: () => void }) {
  const [idx, setIdx] = useState(startIndex);
  const stageRef = useRef<HTMLDivElement>(null);
  const [size, setSize] = useState({ w: 0, h: 0 });

  useEffect(() => {
    const el = stageRef.current;
    if (!el) return;
    const ro = new ResizeObserver(() => setSize({ w: el.clientWidth, h: el.clientHeight }));
    ro.observe(el);
    setSize({ w: el.clientWidth, h: el.clientHeight });
    return () => ro.disconnect();
  }, []);

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") onClose();
      else if (e.key === "ArrowLeft") setIdx((i) => (i - 1 + assets.length) % assets.length);
      else if (e.key === "ArrowRight") setIdx((i) => (i + 1) % assets.length);
    }
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [assets.length, onClose]);

  const asset = assets[idx];
  if (!asset) return null;

  const navBtnStyle = { position: "absolute" as const, top: "50%", transform: "translateY(-50%)", background: "rgba(255,255,255,0.1)", color: "#fff", border: "none", width: 48, height: 48, borderRadius: 24, cursor: "pointer", fontSize: 24, lineHeight: 1 };

  return (
    <div
      style={{ position: "fixed", inset: 0, background: "rgba(0,0,0,0.92)", zIndex: 1000, display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center" }}
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <button
        title="Close (Esc)"
        onClick={onClose}
        style={{ position: "absolute", top: 16, right: 16, background: "rgba(255,255,255,0.1)", color: "#fff", border: "none", width: 40, height: 40, borderRadius: 20, cursor: "pointer", fontSize: 20, lineHeight: 1 }}
      >
        ✕
      </button>
      <button title="Previous (←)" onClick={() => setIdx((i) => (i - 1 + assets.length) % assets.length)} style={{ ...navBtnStyle, left: 16 }}>
        ‹
      </button>
      <button title="Next (→)" onClick={() => setIdx((i) => (i + 1) % assets.length)} style={{ ...navBtnStyle, right: 16 }}>
        ›
      </button>
      <div ref={stageRef} style={{ position: "relative", width: "90vw", height: "80vh", display: "flex", alignItems: "center", justifyContent: "center", color: "#9ca3af", fontSize: 14 }}>
        <ViewerStage key={idx} asset={asset} containerSize={size} />
      </div>
      <div style={{ color: "#e5e7eb", fontSize: 13, marginTop: 12, textAlign: "center", maxWidth: "80vw", lineHeight: 1.4 }}>
        {idx + 1} / {assets.length}
        {asset.selectedBox ? " · ✂ cropped" : ""}
        {asset.description ? ` — ${asset.description}` : ""}
      </div>
    </div>
  );
}
