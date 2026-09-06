"use client";

// Renders a portfolio Asset's normalized image, cropped to its selectedBox (if any) via the
// classic "oversized + offset" CSS trick (no server-side cropping endpoint exists). Shared by the
// portfolio list's 4-cell mosaic preview and the detail view's asset tiles. The originalId lookup
// is gated by useInViewport for the same reason LazyThumb's is — a portfolio with many assets
// shouldn't fire that many lookups at once.
import { useEffect, useState, type CSSProperties } from "react";
import { useAuth } from "../lib/keycloak-auth";
import { useInViewport } from "../hooks/useInViewport";
import { useMediaAccessKey } from "../hooks/useMediaAccessKey";
import { displayRotationDegrees } from "../lib/orientation";
import type { Asset } from "../lib/api-client";

export function AssetThumb({ asset, resolveRotation = false }: { asset: Asset; resolveRotation?: boolean }) {
  const { api } = useAuth();
  const [ref, inView] = useInViewport<HTMLDivElement>();
  const accessKey = useMediaAccessKey(inView ? asset.originalId : null);
  const [rotateDeg, setRotateDeg] = useState(0);

  useEffect(() => {
    if (!resolveRotation || !accessKey) return;
    let cancelled = false;
    api
      .getMediaByKey(accessKey)
      .then((m) => {
        if (!cancelled) setRotateDeg(displayRotationDegrees(m));
      })
      .catch(() => {
        /* best-effort rotation hint */
      });
    return () => {
      cancelled = true;
    };
  }, [resolveRotation, accessKey, api]);

  const b = asset.selectedBox && asset.selectedBox.width > 0 && asset.selectedBox.height > 0 ? asset.selectedBox : null;
  const imgStyle: CSSProperties = b
    ? { position: "absolute", width: `${(1 / b.width) * 100}%`, height: `${(1 / b.height) * 100}%`, left: `${-(b.x / b.width) * 100}%`, top: `${-(b.y / b.height) * 100}%`, objectFit: "cover" }
    : { position: "absolute", inset: 0, width: "100%", height: "100%", objectFit: "cover" };
  if (rotateDeg) imgStyle.transform = `rotate(${rotateDeg}deg)`;

  return (
    <div ref={ref} style={{ position: "absolute", inset: 0 }}>
      {accessKey && <img src={api.mediaNormalizedUrl(accessKey)} loading="lazy" decoding="async" draggable={false} style={imgStyle} alt="" />}
    </div>
  );
}
