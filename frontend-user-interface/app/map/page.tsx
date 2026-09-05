"use client";

// Leaflet + marker-clustering stays fully imperative (a useEffect-managed map instance), matching
// how it already worked — this is imperative logic (batched marker loading, lazily-resolved
// popups) that doesn't fit a declarative React-Leaflet wrapper any better than it fit vanilla DOM
// updates. Only the library import moved, from CDN globals to npm packages.
import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import type * as Leaflet from "leaflet";
import type { MediaLocation } from "../../lib/api-client";
import { useAuth } from "../../lib/keycloak-auth";
import { fixLeafletDefaultIcon } from "../../lib/leaflet-fix-icons";
import { getCachedMapLocations, setCachedMapLocations } from "../../lib/map-cache";

const MARKER_BATCH_SIZE = 200;
const MARKER_BATCH_INTERVAL_MS = 250;

export default function MapPage() {
  const { api } = useAuth();
  const router = useRouter();
  const mapDivRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<Leaflet.Map | null>(null);
  const clusterRef = useRef<Leaflet.MarkerClusterGroup | null>(null);
  const markersByKeyRef = useRef<Map<string, Leaflet.Marker>>(new Map());
  const addedKeysRef = useRef<Set<string>>(new Set());
  const bagNameCacheRef = useRef<Map<string, Promise<string>>>(new Map());
  const loadingRef = useRef(false);
  const [status, setStatus] = useState("");

  async function getBagNameCached(bagId: string | undefined): Promise<string> {
    if (!bagId) return "";
    const cache = bagNameCacheRef.current;
    let p = cache.get(bagId);
    if (!p) {
      p = api
        .getBag(bagId)
        .then((bag) => bag?.name || "")
        .catch(() => "");
      cache.set(bagId, p);
    }
    return p;
  }

  function addMarker(L: typeof Leaflet, m: MediaLocation): Leaflet.Marker | null {
    if (addedKeysRef.current.has(m.accessKey)) return null;
    addedKeysRef.current.add(m.accessKey);
    const marker = L.marker([m.latitude, m.longitude]);
    const date = m.shootDateTime || "";
    const starred = m.starred ? "⭐" : "☆";
    marker.bindPopup(`
      <div style="min-width:200px">
        <div style="font-weight:600"><span id="evname-${m.accessKey}">…</span> ${starred}</div>
        <div style="font-size:12px;color:#555">${date ? new Date(date).toLocaleString() : ""}</div>
        <img id="thumb-${m.accessKey}" alt="media" style="width:100%;height:auto;border-radius:6px;margin-top:6px"/>
        <button id="goto-${m.accessKey}" style="margin-top:6px">Open</button>
      </div>
    `);
    marker.on("popupopen", async () => {
      const goBtn = document.getElementById(`goto-${m.accessKey}`);
      if (goBtn) goBtn.onclick = () => router.push(`/?media=${encodeURIComponent(m.accessKey)}`);
      const evEl = document.getElementById(`evname-${m.accessKey}`);
      if (evEl) evEl.textContent = (await getBagNameCached(m.bagId)) || "(no bag)";
      const imgEl = document.getElementById(`thumb-${m.accessKey}`) as HTMLImageElement | null;
      if (imgEl) imgEl.src = api.mediaNormalizedUrl(m.accessKey);
    });
    markersByKeyRef.current.set(m.accessKey, marker);
    return marker;
  }

  // `forceRefetch` is for the explicit Refresh button — everything else (including the very first
  // mount of any given visit) prefers the module-level cache (lib/map-cache.ts) so navigating away
  // from and back to this tab doesn't re-stream potentially thousands of rows every time.
  function loadMapData(L: typeof Leaflet, opts: { clear?: boolean; forceRefetch?: boolean } = {}) {
    const cluster = clusterRef.current;
    if (!cluster || loadingRef.current) {
      if (loadingRef.current) setStatus("Already loading…");
      return;
    }
    if (opts.clear) {
      cluster.clearLayers();
      addedKeysRef.current.clear();
    }

    const cached = opts.forceRefetch ? null : getCachedMapLocations();
    if (cached) {
      const markers = cached.map((m) => addMarker(L, m)).filter((m): m is Leaflet.Marker => !!m);
      cluster.addLayers(markers);
      setStatus(`Done. Markers: ${addedKeysRef.current.size} (cached)`);
      return;
    }

    setStatus("Loading medias with location…");
    loadingRef.current = true;
    const collected: MediaLocation[] = [];
    let pendingBatch: Leaflet.Marker[] = [];
    let lastFlush = performance.now();
    const flush = () => {
      if (pendingBatch.length === 0) return;
      const batch = pendingBatch;
      pendingBatch = [];
      cluster.addLayers(batch);
      lastFlush = performance.now();
      setStatus(`Loaded ${addedKeysRef.current.size} markers…`);
    };

    api
      .mediasLocations((m) => {
        collected.push(m);
        const marker = addMarker(L, m);
        if (!marker) return;
        pendingBatch.push(marker);
        const now = performance.now();
        if (pendingBatch.length >= MARKER_BATCH_SIZE || now - lastFlush >= MARKER_BATCH_INTERVAL_MS) flush();
      })
      .then(() => {
        flush();
        loadingRef.current = false;
        setCachedMapLocations(collected);
        setStatus(`Done. Markers: ${addedKeysRef.current.size}`);
      })
      .catch(() => {
        flush();
        loadingRef.current = false;
        setStatus(`Load interrupted. Markers: ${addedKeysRef.current.size}`);
      });
  }

  useEffect(() => {
    let disposed = false;
    (async () => {
      const L = (await import("leaflet")).default;
      fixLeafletDefaultIcon(L);
      await import("leaflet.markercluster");
      if (disposed || !mapDivRef.current) return;
      const map = L.map(mapDivRef.current).setView([20, 0], 2);
      L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", { attribution: "&copy; OpenStreetMap" }).addTo(map);
      const cluster = (L as unknown as { markerClusterGroup: () => Leaflet.MarkerClusterGroup }).markerClusterGroup();
      map.addLayer(cluster);
      mapRef.current = map;
      clusterRef.current = cluster;
      setTimeout(() => map.invalidateSize(true), 0);
      const onResize = () => map.invalidateSize(true);
      window.addEventListener("resize", onResize);
      loadMapData(L);
      return () => window.removeEventListener("resize", onResize);
    })();
    return () => {
      disposed = true;
      mapRef.current?.remove();
      mapRef.current = null;
      clusterRef.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function handleRefresh() {
    const L = (await import("leaflet")).default;
    loadMapData(L, { forceRefetch: true });
  }

  return (
    <section className="page map-page">
      <div className="list-actions">
        <button onClick={handleRefresh}>↻ Refresh map</button>
      </div>
      <div id="map" ref={mapDivRef} />
      <div className="status">{status}</div>
    </section>
  );
}
