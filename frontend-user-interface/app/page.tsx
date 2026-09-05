"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useCallback, useEffect, useRef, useState, type CSSProperties } from "react";
import { AddToPortfolioModal } from "../components/AddToPortfolioModal";
import { FaceEditModal } from "../components/FaceEditModal";
import { FacesOverlay, type ImageRect } from "../components/FacesOverlay";
import { LocationPin } from "../components/LocationPin";
import { MediaEditModal } from "../components/MediaEditModal";
import { useCurrentMedia } from "../hooks/useCurrentMedia";
import { usePersonsMap } from "../hooks/usePersons";
import { useAuth } from "../lib/keycloak-auth";
import { degreesToOrientation, orientationToDegrees } from "../lib/orientation";
import { pushRecentPersonId } from "../lib/recent-persons";
import { showError, showSuccess, showWarning } from "../lib/toast";
import type { DetectedFace, Media } from "../lib/api-client";

const ZOOM_MIN = 1;
const ZOOM_MAX = 6;
const ZOOM_STEP = 1.25;
const PAN_STEP = 60;
const LAST_MEDIA_KEY = "viewer.lastMediaAccessKey";
const FACES_ENABLED_KEY = "viewer.facesEnabled";
const SLIDESHOW_SECS_KEY = "ui.slideshow.secs";
const SLIDESHOW_MODE_KEY = "ui.slideshow.mode";

function cameraInfo(media: Media): string {
  const parts: string[] = [];
  const o = media.original;
  if (o?.aperture) parts.push(`f/${o.aperture < 10 ? o.aperture.toFixed(1) : Math.round(o.aperture)}`);
  if (o?.exposureTime && o.exposureTime.numerator > 0 && o.exposureTime.denominator > 0) {
    const { numerator, denominator } = o.exposureTime;
    if (denominator <= numerator) {
      const seconds = numerator / denominator;
      parts.push(`${Number.isInteger(seconds) ? seconds : seconds.toFixed(1)}s`);
    } else {
      parts.push(`1/${Math.round(denominator / numerator)}s`);
    }
  }
  if (o?.iso) parts.push(`iso${Math.round(o.iso)}`);
  if (o?.focalLength) parts.push(`${Math.round(o.focalLength)}mm`);
  if (o?.cameraName) parts.push(o.cameraName);
  return parts.length > 0 ? parts.join(", ") : "-";
}

export default function ViewerPage() {
  return (
    <Suspense fallback={<section className="page" />}>
      <ViewerPageInner />
    </Suspense>
  );
}

function ViewerPageInner() {
  const { api } = useAuth();
  const router = useRouter();
  const searchParams = useSearchParams();
  const { media, navigate, loadByKey, setMedia } = useCurrentMedia();
  const personsMap = usePersonsMap();

  const containerRef = useRef<HTMLDivElement>(null);
  const imgRef = useRef<HTMLImageElement>(null);

  const [zoom, setZoom] = useState(1);
  const [panX, setPanX] = useState(0);
  const [panY, setPanY] = useState(0);
  const [isFullscreen, setIsFullscreen] = useState(false);
  const [imgBox, setImgBox] = useState<{ width: number; height: number } | null>(null);
  const [imageRect, setImageRect] = useState<ImageRect>({ left: 0, top: 0, width: 0, height: 0 });
  const [facesEnabled, setFacesEnabled] = useState(false);
  const [currentFaces, setCurrentFaces] = useState<DetectedFace[]>([]);
  const facesSeqRef = useRef(0);
  const [editingFace, setEditingFace] = useState<DetectedFace | null>(null);
  const [editingMedia, setEditingMedia] = useState(false);
  const [addingToPortfolio, setAddingToPortfolio] = useState(false);
  const [addFaceMode, setAddFaceMode] = useState(false);
  const [addFaceDraw, setAddFaceDraw] = useState<{ left: number; top: number; width: number; height: number } | null>(null);
  const addFaceStartRef = useRef<{ x: number; y: number } | null>(null);
  const [slideshowPlaying, setSlideshowPlaying] = useState(false);
  const [slideshowSecs, setSlideshowSecs] = useState(20);
  const [slideshowMode, setSlideshowMode] = useState<"next" | "random">("next");
  const slideshowTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const [ownerName, setOwnerName] = useState("-");

  const mediaRef = useRef<Media | null>(null);
  mediaRef.current = media;
  const zoomRef = useRef(zoom);
  zoomRef.current = zoom;
  const panRef = useRef({ x: panX, y: panY });
  panRef.current = { x: panX, y: panY };
  const imageRectRef = useRef(imageRect);
  imageRectRef.current = imageRect;
  const addFaceModeRef = useRef(addFaceMode);
  addFaceModeRef.current = addFaceMode;
  const slideshowPlayingRef = useRef(slideshowPlaying);
  slideshowPlayingRef.current = slideshowPlaying;
  const slideshowSecsRef = useRef(slideshowSecs);
  slideshowSecsRef.current = slideshowSecs;
  const slideshowModeRef = useRef(slideshowMode);
  slideshowModeRef.current = slideshowMode;

  const rotateDeg = orientationToDegrees(media?.orientation);
  const wantsOriginal = isFullscreen || zoom > 1;
  const imgSrc = media ? (wantsOriginal ? api.mediaOriginalUrl(media.accessKey) : api.mediaNormalizedUrl(media.accessKey)) : undefined;

  // ---- Initial media load: ?media=<key> (from mosaic/map/persons) > last viewed > random. ----
  useEffect(() => {
    (async () => {
      const mediaParam = searchParams.get("media");
      try {
        if (mediaParam) {
          await loadByKey(mediaParam);
          return;
        }
        const last = localStorage.getItem(LAST_MEDIA_KEY);
        if (last) {
          await loadByKey(last);
          return;
        }
        await navigate("random");
      } catch {
        try {
          await navigate("random");
        } catch {
          /* nothing to show */
        }
      }
    })();
    try {
      const saved = localStorage.getItem(FACES_ENABLED_KEY);
      if (saved != null) setFacesEnabled(saved === "1");
    } catch {
      /* ignore */
    }
    try {
      const secs = parseInt(localStorage.getItem(SLIDESHOW_SECS_KEY) || "20", 10);
      if (Number.isFinite(secs)) setSlideshowSecs(secs);
      const mode = localStorage.getItem(SLIDESHOW_MODE_KEY);
      if (mode === "random" || mode === "next") setSlideshowMode(mode);
    } catch {
      /* ignore */
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (media) {
      try {
        localStorage.setItem(LAST_MEDIA_KEY, media.accessKey);
      } catch {
        /* ignore */
      }
    }
  }, [media?.accessKey]);

  // ---- Owner lookup (store -> owner), refetched per displayed photo. ----
  useEffect(() => {
    let cancelled = false;
    if (!media?.original?.storeId) {
      setOwnerName("-");
      return;
    }
    setOwnerName("Loading...");
    (async () => {
      try {
        const store = await api.getStore(media.original.storeId);
        if (!store.ownerId) {
          if (!cancelled) setOwnerName("-");
          return;
        }
        const owner = await api.getOwner(store.ownerId);
        if (!cancelled) setOwnerName(owner.firstName && owner.lastName ? `${owner.firstName} ${owner.lastName}` : "-");
      } catch {
        if (!cancelled) setOwnerName("-");
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [media?.original?.storeId, api]);

  // ---- Faces ----
  const loadFaces = useCallback(
    async (accessKey: string) => {
      const seq = ++facesSeqRef.current;
      try {
        const originalFaces = await api.getMediaFaces(accessKey);
        const ids = originalFaces.facesIds || [];
        const details = (await Promise.all(ids.map((id) => api.getFace(id).catch(() => null)))).filter((f): f is DetectedFace => !!f);
        if (seq === facesSeqRef.current) setCurrentFaces(details);
      } catch {
        if (seq === facesSeqRef.current) setCurrentFaces([]);
      }
    },
    [api]
  );

  useEffect(() => {
    if (facesEnabled && media) loadFaces(media.accessKey);
    else setCurrentFaces([]);
  }, [facesEnabled, media?.accessKey, loadFaces]);

  function toggleFaces() {
    const next = !facesEnabled;
    setFacesEnabled(next);
    try {
      localStorage.setItem(FACES_ENABLED_KEY, next ? "1" : "0");
    } catch {
      /* ignore */
    }
  }

  // ---- Image rect + rotation box measurement ----
  const recompute = useCallback(() => {
    const cont = containerRef.current;
    const img = imgRef.current;
    const deg = orientationToDegrees(mediaRef.current?.orientation);
    if (deg === 90 || deg === 270) {
      const cw = cont?.clientWidth || 0;
      const ch = cont?.clientHeight || 0;
      setImgBox(cw > 0 && ch > 0 ? { width: ch, height: cw } : null);
    } else {
      setImgBox(null);
    }
    if (!cont || !img || !img.naturalWidth || !img.naturalHeight) {
      setImageRect({ left: 0, top: 0, width: 0, height: 0 });
      return;
    }
    const cw = cont.clientWidth;
    const ch = cont.clientHeight;
    if (cw <= 0 || ch <= 0) {
      setImageRect({ left: 0, top: 0, width: 0, height: 0 });
      return;
    }
    const swap = deg === 90 || deg === 270;
    const nw = swap ? img.naturalHeight : img.naturalWidth;
    const nh = swap ? img.naturalWidth : img.naturalHeight;
    const scale = Math.min(cw / nw, ch / nh);
    const w = Math.max(0, Math.round(nw * scale));
    const h = Math.max(0, Math.round(nh * scale));
    setImageRect({ left: Math.round((cw - w) / 2), top: Math.round((ch - h) / 2), width: w, height: h });
  }, []);

  function restartKenBurns() {
    const img = imgRef.current;
    if (!img) return;
    img.classList.remove("zooming");
    void img.offsetWidth;
    img.classList.add("zooming");
  }

  function handleImgLoad() {
    recompute();
    const cont = containerRef.current;
    if (cont) cont.style.setProperty("--viewer-zoom-duration", `${slideshowSecsRef.current}s`);
    if (slideshowPlayingRef.current) restartKenBurns();
    else imgRef.current?.classList.remove("zooming");
  }

  useEffect(() => {
    function onResize() {
      recompute();
    }
    window.addEventListener("resize", onResize);
    return () => window.removeEventListener("resize", onResize);
  }, [recompute]);

  useEffect(() => {
    function onFsChange() {
      const cont = containerRef.current;
      setIsFullscreen(!!(document.fullscreenElement && cont && document.fullscreenElement === cont));
      setZoom(1);
      setPanX(0);
      setPanY(0);
      setTimeout(recompute, 0);
    }
    document.addEventListener("fullscreenchange", onFsChange);
    return () => document.removeEventListener("fullscreenchange", onFsChange);
  }, [recompute]);

  // ---- Zoom / pan ----
  function clampAndApplyZoom(newZoom: number, newPanX: number, newPanY: number) {
    const cont = containerRef.current;
    const cw = cont?.clientWidth || 0;
    const ch = cont?.clientHeight || 0;
    const maxX = Math.max(0, ((newZoom - 1) * cw) / 2);
    const maxY = Math.max(0, ((newZoom - 1) * ch) / 2);
    setZoom(newZoom);
    setPanX(Math.min(maxX, Math.max(-maxX, newPanX)));
    setPanY(Math.min(maxY, Math.max(-maxY, newPanY)));
  }

  function zoomBy(factor: number, clientX?: number, clientY?: number) {
    const cont = containerRef.current;
    if (!cont || !mediaRef.current) return;
    const s0 = zoomRef.current;
    const s1 = Math.min(ZOOM_MAX, Math.max(ZOOM_MIN, s0 * factor));
    if (s1 === s0) return;
    const rect = cont.getBoundingClientRect();
    let ux = 0;
    let uy = 0;
    if (typeof clientX === "number" && typeof clientY === "number") {
      ux = clientX - (rect.left + rect.width / 2);
      uy = clientY - (rect.top + rect.height / 2);
    }
    const ratio = s1 / s0;
    let newPanX = panRef.current.x * ratio + ux * (1 - ratio);
    let newPanY = panRef.current.y * ratio + uy * (1 - ratio);
    if (s1 === 1) {
      newPanX = 0;
      newPanY = 0;
    }
    clampAndApplyZoom(s1, newPanX, newPanY);
  }
  function resetZoom() {
    clampAndApplyZoom(1, 0, 0);
  }
  function panBy(dx: number, dy: number) {
    if (zoomRef.current <= 1) return;
    clampAndApplyZoom(zoomRef.current, panRef.current.x + dx, panRef.current.y + dy);
  }

  useEffect(() => {
    recompute();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [zoom, panX, panY]);

  // Drag-to-pan when zoomed in.
  useEffect(() => {
    let panning = false;
    let startX = 0;
    let startY = 0;
    let origX = 0;
    let origY = 0;
    const cont = containerRef.current;
    function onDown(e: MouseEvent) {
      if (e.button !== 0 || addFaceModeRef.current || zoomRef.current <= 1) return;
      panning = true;
      startX = e.clientX;
      startY = e.clientY;
      origX = panRef.current.x;
      origY = panRef.current.y;
      if (cont) cont.style.cursor = "grabbing";
      e.preventDefault();
    }
    function onMove(e: MouseEvent) {
      if (!panning) return;
      setPanX(origX + (e.clientX - startX));
      setPanY(origY + (e.clientY - startY));
    }
    function onUp() {
      if (!panning) return;
      panning = false;
      if (cont) cont.style.cursor = zoomRef.current > 1 ? "grab" : "";
    }
    cont?.addEventListener("mousedown", onDown);
    window.addEventListener("mousemove", onMove);
    window.addEventListener("mouseup", onUp);
    return () => {
      cont?.removeEventListener("mousedown", onDown);
      window.removeEventListener("mousemove", onMove);
      window.removeEventListener("mouseup", onUp);
    };
  }, []);

  // Mouse wheel zoom toward the cursor.
  useEffect(() => {
    const cont = containerRef.current;
    function onWheel(e: WheelEvent) {
      if (!mediaRef.current) return;
      e.preventDefault();
      zoomBy(e.deltaY < 0 ? ZOOM_STEP : 1 / ZOOM_STEP, e.clientX, e.clientY);
    }
    cont?.addEventListener("wheel", onWheel, { passive: false });
    return () => cont?.removeEventListener("wheel", onWheel);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // ---- Add-face draw mode ----
  function cancelAddFaceMode() {
    setAddFaceMode(false);
    setAddFaceDraw(null);
    addFaceStartRef.current = null;
  }
  function toggleAddFaceMode() {
    if (addFaceMode) {
      cancelAddFaceMode();
      return;
    }
    if (!mediaRef.current) {
      showWarning("No media to add a face to");
      return;
    }
    setAddFaceMode(true);
  }
  useEffect(() => {
    if (!addFaceMode) return;
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") cancelAddFaceMode();
    }
    document.addEventListener("keydown", onKey, { capture: true });
    document.addEventListener("fullscreenchange", cancelAddFaceMode);
    window.addEventListener("resize", cancelAddFaceMode);
    return () => {
      document.removeEventListener("keydown", onKey, { capture: true });
      document.removeEventListener("fullscreenchange", cancelAddFaceMode);
      window.removeEventListener("resize", cancelAddFaceMode);
    };
  }, [addFaceMode]);

  function handleContainerPointerDown(e: React.MouseEvent) {
    if (!addFaceMode) return;
    const cont = containerRef.current;
    if (!cont) return;
    const contRect = cont.getBoundingClientRect();
    const x = e.clientX - contRect.left;
    const y = e.clientY - contRect.top;
    const rect = imageRectRef.current;
    if (x < rect.left || y < rect.top || x > rect.left + rect.width || y > rect.top + rect.height) {
      showWarning("Start dragging inside the photo area");
      return;
    }
    addFaceStartRef.current = { x, y };
    setAddFaceDraw({ left: x, top: y, width: 0, height: 0 });
    e.preventDefault();
  }
  function handleContainerPointerMove(e: React.MouseEvent) {
    if (!addFaceMode || !addFaceStartRef.current) return;
    const cont = containerRef.current;
    if (!cont) return;
    const contRect = cont.getBoundingClientRect();
    const rect = imageRectRef.current;
    let x = e.clientX - contRect.left;
    let y = e.clientY - contRect.top;
    x = Math.max(rect.left, Math.min(rect.left + rect.width, x));
    y = Math.max(rect.top, Math.min(rect.top + rect.height, y));
    const start = addFaceStartRef.current;
    const sx = Math.max(rect.left, Math.min(rect.left + rect.width, start.x));
    const sy = Math.max(rect.top, Math.min(rect.top + rect.height, start.y));
    setAddFaceDraw({ left: Math.min(sx, x), top: Math.min(sy, y), width: Math.abs(x - sx), height: Math.abs(y - sy) });
  }
  useEffect(() => {
    const addFacePostingRef = { current: false };
    async function onUp(e: MouseEvent) {
      if (!addFaceModeRef.current || !addFaceStartRef.current || addFacePostingRef.current) return;
      addFacePostingRef.current = true;
      const cont = containerRef.current;
      if (!cont) {
        addFacePostingRef.current = false;
        return;
      }
      const contRect = cont.getBoundingClientRect();
      const rect = imageRectRef.current;
      let x2 = e.clientX - contRect.left;
      let y2 = e.clientY - contRect.top;
      x2 = Math.max(rect.left, Math.min(rect.left + rect.width, x2));
      y2 = Math.max(rect.top, Math.min(rect.top + rect.height, y2));
      const start = addFaceStartRef.current;
      const sx = Math.max(rect.left, Math.min(rect.left + rect.width, start.x));
      const sy = Math.max(rect.top, Math.min(rect.top + rect.height, start.y));
      const left = Math.min(sx, x2);
      const top = Math.min(sy, y2);
      const widthPx = Math.abs(x2 - sx);
      const heightPx = Math.abs(y2 - sy);
      const wRel = rect.width > 0 ? widthPx / rect.width : 0;
      const hRel = rect.height > 0 ? heightPx / rect.height : 0;
      if (widthPx < 8 || heightPx < 8 || wRel < 0.005 || hRel < 0.005) {
        cancelAddFaceMode();
        showWarning("Box too small, canceled");
        addFacePostingRef.current = false;
        return;
      }
      const box = {
        x: Math.max(0, Math.min(1, rect.width > 0 ? (left - rect.left) / rect.width : 0)),
        y: Math.max(0, Math.min(1, rect.height > 0 ? (top - rect.top) / rect.height : 0)),
        width: Math.max(0, Math.min(1, wRel)),
        height: Math.max(0, Math.min(1, hRel)),
      };
      const originalId = mediaRef.current?.original.id;
      if (!originalId) {
        cancelAddFaceMode();
        showError("Unable to resolve original photo id for this media");
        addFacePostingRef.current = false;
        return;
      }
      try {
        const created = await api.createFace({ originalId, box });
        setCurrentFaces((prev) => (prev.some((f) => f.faceId === created.faceId) ? prev : [...prev, created]));
        showSuccess("Face created");
      } catch {
        showError("Failed to create face");
      } finally {
        addFacePostingRef.current = false;
        cancelAddFaceMode();
      }
    }
    window.addEventListener("mouseup", onUp);
    return () => window.removeEventListener("mouseup", onUp);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [api]);

  // ---- Faces overlay actions ----
  async function confirmInferredFace(face: DetectedFace) {
    if (!face.inferredIdentifiedPersonId) return;
    const personId = face.inferredIdentifiedPersonId;
    try {
      await api.setFacePerson(face.faceId, personId);
      pushRecentPersonId(personId);
      setCurrentFaces((prev) => prev.map((f) => (f.faceId === face.faceId ? { ...f, identifiedPersonId: personId, inferredIdentifiedPersonId: undefined } : f)));
      showSuccess("Confirmed inferred person for this face");
    } catch {
      showError("Failed to confirm inferred person");
    }
  }

  const inferredPending = currentFaces.filter((f) => !f.identifiedPersonId && !f.inferredIgnore && f.inferredIdentifiedPersonId);

  async function confirmAllInferred() {
    if (inferredPending.length === 0) return;
    if (!confirm(`Confirm all ${inferredPending.length} inferred face${inferredPending.length > 1 ? "s" : ""} on this image?`)) return;
    let ok = 0;
    let ko = 0;
    let index = 0;
    const targets = inferredPending;
    async function runNext(): Promise<void> {
      if (index >= targets.length) return;
      const f = targets[index++];
      try {
        await api.setFacePerson(f.faceId, f.inferredIdentifiedPersonId!);
        pushRecentPersonId(f.inferredIdentifiedPersonId!);
        ok++;
      } catch {
        ko++;
      }
      return runNext();
    }
    await Promise.all(Array.from({ length: Math.min(6, targets.length) }, runNext));
    setCurrentFaces((prev) => prev.map((f) => (targets.some((t) => t.faceId === f.faceId) ? { ...f, identifiedPersonId: f.inferredIdentifiedPersonId, inferredIdentifiedPersonId: undefined } : f)));
    if (ko === 0) showSuccess(`Confirmed ${ok} face${ok > 1 ? "s" : ""}`);
    else if (ok === 0) showError("Failed to confirm inferred faces");
    else showWarning(`Confirmed ${ok}, failed ${ko}`);
  }

  // ---- Star / rotate ----
  async function toggleStar() {
    if (!media) return;
    const target = !media.starred;
    setMedia({ ...media, starred: target });
    try {
      await api.updateMediaStarred(media.accessKey, target);
    } catch {
      setMedia({ ...media, starred: !target });
      showError("Failed to update starred");
    }
  }

  async function rotateBy(deltaDeg: number) {
    if (!media) {
      showWarning("No media loaded");
      return;
    }
    const currentDeg = orientationToDegrees(media.orientation);
    const newDeg = (((currentDeg + deltaDeg) % 360) + 360) % 360;
    const newOrientation = degreesToOrientation(newDeg);
    const previous = media.orientation;
    setMedia({ ...media, orientation: newOrientation });
    try {
      await api.updateMedia(media.accessKey, {
        starred: !!media.starred,
        orientation: newOrientation,
        keywords: media.keywords || [],
        description: media.description || undefined,
        shootDateTime: media.shootDateTime,
        userDefinedLocation: media.userDefinedLocation,
      });
    } catch {
      setMedia({ ...media, orientation: previous });
      showError("Failed to rotate media");
    }
  }

  function toggleFullscreen() {
    const cont = containerRef.current;
    if (!document.fullscreenElement) cont?.requestFullscreen?.();
    else document.exitFullscreen?.();
  }

  function downloadOriginal() {
    if (!media) return;
    const a = document.createElement("a");
    a.href = api.mediaOriginalUrl(media.accessKey);
    a.download = `sotohp_${media.accessKey}.jpg`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
  }

  // ---- Slideshow ----
  function stopSlideshow() {
    setSlideshowPlaying(false);
    if (slideshowTimerRef.current) {
      clearTimeout(slideshowTimerRef.current);
      slideshowTimerRef.current = null;
    }
    imgRef.current?.classList.remove("zooming");
  }
  function scheduleNextTick() {
    if (!slideshowPlayingRef.current) return;
    const delay = slideshowSecsRef.current * 1000;
    slideshowTimerRef.current = setTimeout(async () => {
      try {
        if (slideshowModeRef.current === "random") await navigate("random");
        else if (mediaRef.current) await navigate("next", mediaRef.current.accessKey);
        else await navigate("first");
      } finally {
        if (slideshowPlayingRef.current) scheduleNextTick();
      }
    }, delay);
  }
  function toggleSlideshow() {
    if (slideshowPlaying) {
      stopSlideshow();
      return;
    }
    setSlideshowPlaying(true);
    const cont = containerRef.current;
    if (cont) cont.style.setProperty("--viewer-zoom-duration", `${slideshowSecs}s`);
    restartKenBurns();
    scheduleNextTick();
  }
  function persistSlideshowSecs(secs: number) {
    setSlideshowSecs(secs);
    try {
      localStorage.setItem(SLIDESHOW_SECS_KEY, String(secs));
    } catch {
      /* ignore */
    }
    if (slideshowPlaying) {
      if (slideshowTimerRef.current) clearTimeout(slideshowTimerRef.current);
      scheduleNextTick();
    }
  }
  function persistSlideshowMode(mode: "next" | "random") {
    setSlideshowMode(mode);
    try {
      localStorage.setItem(SLIDESHOW_MODE_KEY, mode);
    } catch {
      /* ignore */
    }
    if (slideshowPlaying) {
      if (slideshowTimerRef.current) clearTimeout(slideshowTimerRef.current);
      scheduleNextTick();
    }
  }

  // ---- Keyboard shortcuts ----
  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      const t = e.target as HTMLElement | null;
      if (t && /^(INPUT|TEXTAREA|SELECT)$/.test(t.tagName)) return;
      if (t?.isContentEditable) return;
      if (e.altKey) return; // let the global tab-cycling shortcut handle Alt+PageUp/Down
      let handled = true;
      switch (e.key) {
        case " ":
          toggleSlideshow();
          break;
        case "Home":
          navigate("first");
          break;
        case "End":
          navigate("last");
          break;
        case "PageDown":
          if (mediaRef.current) navigate("next", mediaRef.current.accessKey);
          else navigate("first");
          break;
        case "PageUp":
          if (mediaRef.current) navigate("previous", mediaRef.current.accessKey);
          else navigate("last");
          break;
        case "+":
        case "=":
          zoomBy(ZOOM_STEP);
          break;
        case "-":
        case "_":
          zoomBy(1 / ZOOM_STEP);
          break;
        case "0":
          resetZoom();
          break;
        case "ArrowLeft":
          if (zoomRef.current > 1) panBy(PAN_STEP, 0);
          else handled = false;
          break;
        case "ArrowRight":
          if (zoomRef.current > 1) panBy(-PAN_STEP, 0);
          else handled = false;
          break;
        case "ArrowUp":
          if (zoomRef.current > 1) panBy(0, PAN_STEP);
          else handled = false;
          break;
        case "ArrowDown":
          if (zoomRef.current > 1) panBy(0, -PAN_STEP);
          else handled = false;
          break;
        default:
          handled = false;
      }
      if (handled) {
        e.preventDefault();
        e.stopPropagation();
      }
    }
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [slideshowPlaying]);

  const hasLoc = !!(media?.location || media?.userDefinedLocation || media?.deductedLocation);
  let pinColor = "#ef4444";
  let locLabel = "No";
  if (media?.location) {
    pinColor = "#10b981";
    locLabel = "Known";
  } else if (media?.userDefinedLocation || media?.deductedLocation) {
    pinColor = "#f59e0b";
    locLabel = "Estimated";
  }
  const dateTs = media?.shootDateTime || media?.original.cameraShootDateTime || null;

  const imgStyle: CSSProperties = {
    ["--pan-x" as string]: `${panX}px`,
    ["--pan-y" as string]: `${panY}px`,
    ["--user-zoom" as string]: zoom,
    ["--img-rotate" as string]: `${rotateDeg}deg`,
    ...(imgBox ? { width: imgBox.width, height: imgBox.height } : {}),
  };
  const overlayStyle: CSSProperties | undefined = zoom !== 1 || panX !== 0 || panY !== 0 ? { transform: `translate(${panX}px, ${panY}px) scale(${zoom})` } : undefined;

  return (
    <section className="page">
      <div className="viewer">
        <div
          className="image-container"
          ref={containerRef}
          style={{ cursor: addFaceMode ? "crosshair" : undefined }}
          onMouseDown={handleContainerPointerDown}
          onMouseMove={handleContainerPointerMove}
        >
          {imgSrc && <img id="main-image" ref={imgRef} src={imgSrc} alt="media" style={imgStyle} onLoad={handleImgLoad} />}

          <div className="fs-overlay" id="fs-overlay">
            <div className="title">
              {media?.starred ? "⭐ " : "☆ "}
              {media?.bag ? media.bag.name || "(no name)" : "-"}
              <LocationPin color={pinColor} />
            </div>
          </div>

          {facesEnabled && (
            <div style={overlayStyle ? { position: "absolute", inset: 0 } : undefined}>
              <div className="faces-overlay-wrap" style={overlayStyle}>
                <FacesOverlay rect={imageRect} faces={currentFaces} personsMap={personsMap} onConfirmInferred={confirmInferredFace} onEdit={setEditingFace} />
              </div>
            </div>
          )}

          {addFaceDraw && <div className="draw-rect" style={{ left: addFaceDraw.left, top: addFaceDraw.top, width: addFaceDraw.width, height: addFaceDraw.height }} />}

          <div className="img-actions">
            <button className="img-action-btn" title="Edit" onClick={() => setEditingMedia(true)}>
              ✎ Edit
            </button>
            <button className="img-action-btn" title="Rotate right" onClick={() => rotateBy(90)}>
              ⟳
            </button>
            <button className="img-action-btn" title="Rotate left" onClick={() => rotateBy(-90)}>
              ⟲
            </button>
            <button className={`img-action-btn${addFaceMode ? " is-active" : ""}`} title="Add face" onClick={toggleAddFaceMode}>
              + Add face
            </button>
            <button className="img-action-btn img-action-btn--success" title="Add to portfolio" onClick={() => setAddingToPortfolio(true)}>
              ＋ Portfolio
            </button>
            <button className="img-action-btn" title="Download original image" onClick={downloadOriginal}>
              ⬇
            </button>
            {facesEnabled && inferredPending.length > 0 && !addFaceMode && (
              <button className="img-action-btn img-action-btn--warn" title="Confirm all inferred faces" onClick={confirmAllInferred}>
                Confirm all ({inferredPending.length})
              </button>
            )}
          </div>
        </div>

        <div className="sidebar">
          <div className="controls">
            <div className="row">
              <button title="First (Home)" onClick={() => navigate("first")}>
                ⏮️
              </button>
              <button title="Previous (Page Up)" onClick={() => media && navigate("previous", media.accessKey)}>
                ◀️
              </button>
              <button title="Next (Page Down)" onClick={() => media && navigate("next", media.accessKey)}>
                ▶️
              </button>
              <button title="Last (End)" onClick={() => navigate("last")}>
                ⏭️
              </button>
            </div>
            <div className="row">
              <button title="Zoom out (− / mouse wheel down)" aria-label="Zoom out" onClick={() => zoomBy(1 / ZOOM_STEP)}>
                🔍−
              </button>
              <button title="Zoom in (+ / mouse wheel up)" aria-label="Zoom in" onClick={() => zoomBy(ZOOM_STEP)}>
                🔍+
              </button>
              <button title="Reset zoom (0) · arrow keys pan when zoomed in" aria-label="Reset zoom" onClick={resetZoom}>
                {Math.round(zoom * 100)}%
              </button>
            </div>
            <div className="row">
              <button title="Fullscreen" onClick={toggleFullscreen}>
                ⛶
              </button>
              <button title={media?.starred ? "Unstar" : "Star"} onClick={toggleStar}>
                {media?.starred ? "⭐" : "☆"}
              </button>
              <button title="Random" onClick={() => navigate("random")}>
                🎲
              </button>
              <button id="btn-play" title="Play/Pause (Space)" onClick={toggleSlideshow}>
                {slideshowPlaying ? "❚❚" : "▷"}
              </button>
              <button className={facesEnabled ? "active" : ""} title={facesEnabled ? "Hide faces" : "Show faces"} aria-label="Show faces" aria-pressed={facesEnabled} onClick={toggleFaces}>
                🙂
              </button>
              <div className="ss-group">
                <div className="segmented" role="group" aria-label="Slideshow delay">
                  {[10, 20, 30].map((s) => (
                    <button key={s} type="button" className={slideshowSecs === s ? "active" : ""} title={`${s} seconds`} onClick={() => persistSlideshowSecs(s)}>
                      {s}s
                    </button>
                  ))}
                </div>
                <div className="segmented" role="group" aria-label="Slideshow mode">
                  <button type="button" className={slideshowMode === "next" ? "active" : ""} title="Next in order" onClick={() => persistSlideshowMode("next")}>
                    ▶️
                  </button>
                  <button type="button" className={slideshowMode === "random" ? "active" : ""} title="Random" onClick={() => persistSlideshowMode("random")}>
                    🎲
                  </button>
                </div>
              </div>
            </div>
          </div>
          <div className="info">
            <div>
              <strong>Date:</strong>{" "}
              <span
                style={dateTs ? { cursor: "pointer" } : undefined}
                title={dateTs ? "Open in Mosaic at this date" : ""}
                onClick={dateTs ? () => router.push(`/mosaic/?ts=${encodeURIComponent(dateTs)}`) : undefined}
              >
                {dateTs ? new Date(dateTs).toLocaleString() : "-"}
              </span>
            </div>
            <div>
              <strong>Description:</strong> <span title={media?.description || ""}>{media?.description?.trim() || "-"}</span>
            </div>
            <div>
              <strong>Bag:</strong>{" "}
              <span style={media?.bag ? { cursor: "pointer" } : undefined} title={media?.bag ? "Open in Bags" : ""} onClick={media?.bag ? () => router.push("/events/") : undefined}>
                {media?.bag ? media.bag.name || "(no name)" : "-"}
              </span>
            </div>
            <div>
              <strong>Location:</strong>{" "}
              <span style={media?.location ? { cursor: "pointer" } : undefined} title={media?.location ? "Show on map" : ""} onClick={media?.location ? () => router.push("/map/") : undefined}>
                <LocationPin color={pinColor} /> {locLabel}
              </span>
            </div>
            <div>
              <strong>Owner:</strong> <span>{ownerName}</span>
            </div>
            <div>
              <strong>Camera:</strong> <span>{media ? cameraInfo(media) : "-"}</span>
            </div>
            <div>
              <strong>Keywords:</strong>{" "}
              {media?.keywords && media.keywords.length > 0 ? (
                <span className="kw-chips">
                  {media.keywords.map((k) => (
                    <span className="chip" key={k}>
                      {k}
                    </span>
                  ))}
                </span>
              ) : (
                <span>-</span>
              )}
            </div>
          </div>
        </div>
      </div>

      {editingMedia && media && (
        <MediaEditModal
          media={media}
          onClose={() => setEditingMedia(false)}
          onSaved={(updated) => {
            setMedia(updated);
            setEditingMedia(false);
          }}
        />
      )}
      {addingToPortfolio && media && <AddToPortfolioModal media={media} onClose={() => setAddingToPortfolio(false)} />}
      {editingFace && (
        <FaceEditModal
          face={editingFace}
          onClose={() => setEditingFace(null)}
          onChanged={(updated) => setCurrentFaces((prev) => prev.map((f) => (f.faceId === updated.faceId ? updated : f)))}
          onDeleted={(faceId) => setCurrentFaces((prev) => prev.filter((f) => f.faceId !== faceId))}
        />
      )}
    </section>
  );
}
