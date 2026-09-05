"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { useAuth } from "../lib/keycloak-auth";
import { mediaTimestamp } from "../lib/media-timestamp";
import { orientationToDegrees } from "../lib/orientation";
import type { Media } from "../lib/api-client";

export function MosaicTile({ media }: { media: Media }) {
  const { api } = useAuth();
  const router = useRouter();
  const [hiLoaded, setHiLoaded] = useState(false);
  const [wantHi, setWantHi] = useState(false);

  const deg = orientationToDegrees(media.orientation);
  const ts = mediaTimestamp(media);
  const tooltip = [media.bag ? media.bag.name : "(no bag)", ts ? new Date(ts).toLocaleString() : null].filter(Boolean).join(" · ");

  return (
    <div
      className="mosaic-tile"
      data-media-key={media.accessKey}
      style={deg ? ({ "--img-rotate": `${deg}deg` } as React.CSSProperties) : undefined}
      title={tooltip}
      onClick={() => router.push(`/?media=${encodeURIComponent(media.accessKey)}`)}
      onMouseEnter={() => setWantHi(true)}
      onTouchStart={() => setWantHi(true)}
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
