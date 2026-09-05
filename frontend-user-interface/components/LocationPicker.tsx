"use client";

// A Leaflet map with a draggable marker for picking a user-defined location, shared between the
// media-edit and event-edit forms. Leaflet touches `window` at import time, so it's always
// dynamically imported inside an effect — never at module top-level — to stay out of the static
// export's prerender pass (this component only ever mounts client-side anyway, inside a modal
// that's conditionally rendered from user interaction, but the dynamic import keeps the leaflet
// bundle out of pages that never open a location picker at all).
import { useEffect, useRef } from "react";
import type { Location } from "../lib/api-client";
import type * as Leaflet from "leaflet";
import { fixLeafletDefaultIcon } from "../lib/leaflet-fix-icons";

const LAST_LOCATION_KEY = "ui.lastLocation";

function readLastLocation(): Location | null {
  try {
    const raw = localStorage.getItem(LAST_LOCATION_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    if (typeof parsed?.latitude === "number" && typeof parsed?.longitude === "number") return parsed;
  } catch {
    /* ignore malformed storage */
  }
  return null;
}

function saveLastLocation(loc: Location) {
  try {
    localStorage.setItem(LAST_LOCATION_KEY, JSON.stringify({ latitude: loc.latitude, longitude: loc.longitude, altitude: loc.altitude }));
  } catch {
    /* best-effort */
  }
}

export function LocationPicker({
  value,
  onChange,
  mediaLocation,
  fallbackLocation,
}: {
  value: Location | null;
  onChange: (loc: Location | null) => void;
  // The photo's own GPS location (Original.location), used both for "Copy from media location"
  // and — before falling back further — for what's shown when there's no explicit `value` yet.
  mediaLocation?: Location | null;
  // Shown on the map (center + marker) when there's no explicit `value` yet, so the picker isn't
  // just a blank world map for a photo whose location is already known some other way (its own
  // GPS, or — one step further — the bag it's in). Purely visual: dragging or clicking still goes
  // through `onChange` like normal, so nothing is saved as a user override until the user actually
  // does something. `mediaLocation` is preferred over this when both are available.
  fallbackLocation?: Location | null;
}) {
  const mapDivRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<Leaflet.Map | null>(null);
  const markerRef = useRef<Leaflet.Marker | null>(null);
  const onChangeRef = useRef(onChange);
  onChangeRef.current = onChange;
  const displayPosition = value ?? mediaLocation ?? fallbackLocation ?? null;

  // Mount the map once.
  useEffect(() => {
    let disposed = false;
    (async () => {
      const L = (await import("leaflet")).default;
      fixLeafletDefaultIcon(L);
      if (disposed || !mapDivRef.current) return;
      const map = L.map(mapDivRef.current, { keyboard: false }).setView(displayPosition ? [displayPosition.latitude, displayPosition.longitude] : [20, 0], displayPosition ? 13 : 2);
      L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", { attribution: "&copy; OpenStreetMap" }).addTo(map);
      mapRef.current = map;
      if (displayPosition) placeMarker(L, map, displayPosition);
      map.on("click", (e: Leaflet.LeafletMouseEvent) => {
        onChangeRef.current({ latitude: e.latlng.lat, longitude: e.latlng.lng });
      });
    })();

    function placeMarker(L: typeof Leaflet, map: Leaflet.Map, loc: Location) {
      const latlng: [number, number] = [loc.latitude, loc.longitude];
      if (!markerRef.current) {
        markerRef.current = L.marker(latlng, { draggable: true }).addTo(map);
        markerRef.current.on("dragend", () => {
          const ll = markerRef.current!.getLatLng();
          onChangeRef.current({ latitude: ll.lat, longitude: ll.lng });
        });
      } else {
        markerRef.current.setLatLng(latlng);
      }
    }

    return () => {
      disposed = true;
      mapRef.current?.remove();
      mapRef.current = null;
      markerRef.current = null;
    };
    // Intentionally mount-only — later position changes (buttons, map interaction) are applied via
    // the effect below instead of re-running this whole setup.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // React to `displayPosition` changes coming from the action buttons below (not from map interaction).
  useEffect(() => {
    (async () => {
      const L = (await import("leaflet")).default;
      const map = mapRef.current;
      if (!map) return;
      if (displayPosition) {
        const latlng: [number, number] = [displayPosition.latitude, displayPosition.longitude];
        if (!markerRef.current) {
          markerRef.current = L.marker(latlng, { draggable: true }).addTo(map);
          markerRef.current.on("dragend", () => {
            const ll = markerRef.current!.getLatLng();
            onChangeRef.current({ latitude: ll.lat, longitude: ll.lng });
          });
        } else {
          markerRef.current.setLatLng(latlng);
        }
        map.setView(latlng, Math.max(map.getZoom(), 13));
      } else if (markerRef.current) {
        markerRef.current.remove();
        markerRef.current = null;
      }
    })();
  }, [displayPosition?.latitude, displayPosition?.longitude]);

  const lastLocation = typeof window !== "undefined" ? readLastLocation() : null;

  return (
    <div>
      <div style={{ display: "flex", gap: 8, marginBottom: 8, flexWrap: "wrap" }}>
        <button type="button" className="btn btn-soft btn-sm" disabled={!mediaLocation} onClick={() => mediaLocation && onChange(mediaLocation)}>
          Copy from media location
        </button>
        <button type="button" className="btn btn-soft btn-sm" disabled={!value} onClick={() => value && saveLastLocation(value)}>
          Remember selection
        </button>
        <button type="button" className="btn btn-soft btn-sm" disabled={!lastLocation} onClick={() => lastLocation && onChange(lastLocation)}>
          Use last selection
        </button>
        <button type="button" className="btn btn-soft btn-sm" disabled={!value} onClick={() => onChange(null)}>
          Reset location
        </button>
      </div>
      <div className="modal-map" ref={mapDivRef} />
    </div>
  );
}
