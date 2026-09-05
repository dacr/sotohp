import type LeafletModule from "leaflet";

// The npm `leaflet` package's default marker icon resolves its image URLs relative to the JS
// bundle location, which breaks under any bundler (webpack/Turbopack included) — a well-known
// leaflet+bundler issue. Point it at the CDN copy of the same images instead. Idempotent, so it's
// safe to call from every place that creates a Leaflet map.
export function fixLeafletDefaultIcon(L: typeof LeafletModule) {
  const proto = L.Icon.Default.prototype as unknown as { _getIconUrl?: unknown };
  delete proto._getIconUrl;
  L.Icon.Default.mergeOptions({
    iconRetinaUrl: "https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/images/marker-icon-2x.png",
    iconUrl: "https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/images/marker-icon.png",
    shadowUrl: "https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/images/marker-shadow.png",
  });
}
