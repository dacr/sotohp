"use client";

import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { useAuth } from "../lib/keycloak-auth";
import { mediaTimestamp } from "../lib/media-timestamp";
import { orientationToDegrees } from "../lib/orientation";
import type { Media } from "../lib/api-client";

// How long a hovered tile keeps its full-size layer after the pointer leaves. The normalized
// image is far heavier than the miniature, and sweeping the pointer across a grid touches dozens
// of tiles in a second; dropping the layer again means a sweep costs one decoded image at a time
// instead of one per tile passed over. Long enough that moving out and back doesn't re-flash.
const HI_RES_LINGER_MS = 1500;

export function MosaicTile({ media, offset, highlighted = false }: { media: Media; offset: number; highlighted?: boolean }) {
  const { api } = useAuth();
  const router = useRouter();
  const [hiLoaded, setHiLoaded] = useState(false);
  const [wantHi, setWantHi] = useState(false);
  const dropTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => () => { if (dropTimer.current) clearTimeout(dropTimer.current); }, []);

  function holdHiRes() {
    if (dropTimer.current) {
      clearTimeout(dropTimer.current);
      dropTimer.current = null;
    }
    setWantHi(true);
  }

  function releaseHiRes() {
    if (dropTimer.current) clearTimeout(dropTimer.current);
    dropTimer.current = setTimeout(() => {
      setWantHi(false);
      setHiLoaded(false);
    }, HI_RES_LINGER_MS);
  }

  const deg = orientationToDegrees(media.orientation);
  const ts = mediaTimestamp(media);
  const tooltip = [media.bag ? media.bag.name : "(no bag)", ts ? new Date(ts).toLocaleString() : null].filter(Boolean).join(" · ");

  return (
    <div
      className={`mosaic-tile${highlighted ? " highlighted" : ""}`}
      data-media-key={media.accessKey}
      data-offset={offset}
      style={deg ? ({ "--img-rotate": `${deg}deg` } as React.CSSProperties) : undefined}
      title={tooltip}
      onClick={() => router.push(`/?media=${encodeURIComponent(media.accessKey)}`)}
      onMouseEnter={holdHiRes}
      onMouseLeave={releaseHiRes}
      onTouchStart={holdHiRes}
    >
      <img className="layer-mini" src={api.mediaMiniatureUrl(media.accessKey)} loading="lazy" decoding="async" alt="" />
      {wantHi && (
        <img className={`layer-hi${hiLoaded ? " loaded" : ""}`} src={api.mediaNormalizedUrl(media.accessKey)} loading="lazy" decoding="async" alt="" onLoad={() => setHiLoaded(true)} />
      )}
      <button
        type="button"
        className="mosaic-download-btn"
        title="Download original image"
        onClick={(e) => {
          e.stopPropagation();
          const a = document.createElement("a");
          a.href = api.mediaOriginalUrl(media.accessKey);
          a.download = `sotohp_${media.accessKey}.jpg`;
          document.body.appendChild(a);
          a.click();
          document.body.removeChild(a);
        }}
      >
        ⬇
      </button>
    </div>
  );
}
