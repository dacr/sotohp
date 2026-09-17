"use client";

// Clusters tab — groups of visually similar photos, or (alternative view) groups of visually
// similar faces. Face clusters are an unsupervised "you might want to review these" grouping —
// nobody has named these faces yet, unlike the identified-persons view under /persons.
//
// Two independent axes, both driven by query params:
//   `view`    : absent/"photos" (default) or "faces" — which clustering to show
//   `cluster` : absent -> grid of cluster covers (every real cluster, however big); present ->
//               the members of that cluster, capped at MAX_DISPLAYED_CLUSTER_MEMBERS
//
// Photo cluster assignments are produced offline by the `MediaFeaturesClustering` CLI, face
// cluster assignments by `FaceFeaturesClustering`; until either has run its list is empty.
//
// State survives leaving the tab and coming back, at two levels: `view`/`cluster` live in the
// URL, so NavHeader's generic per-tab "last URL" memory already restores them; the "hide
// confirmed" toggles are sessionStorage flags that persist independently of the URL; and each
// scrollable grid below restores its own scroll offset via useScrollRestoration, keyed per
// view/cluster (and per hide-toggle state, since that can change which grid is even mounted).

import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useEffect, useMemo, useRef, useState } from "react";
import { FaceEditModal } from "../../components/FaceEditModal";
import { MosaicTile } from "../../components/MosaicTile";
import { useFaceImageVersion, useSetFacePerson } from "../../hooks/useFaces";
import { usePersonsMap } from "../../hooks/usePersons";
import { useScrollRestoration } from "../../hooks/useScrollRestoration";
import { useAuth } from "../../lib/keycloak-auth";
import { showError } from "../../lib/toast";
import type { DetectedFace, FaceCluster, Media, MediaCluster } from "../../lib/api-client";

// Only singleton "clusters" (size 1, i.e. no real grouping) are hidden from the list — every
// real cluster is shown, however big. Opening one is the expensive part instead: rendering a
// mega-cluster in full would mean thousands of tiles, so the members view stops streaming once
// it has this many. Shared by both photo and face clusters.
const MAX_DISPLAYED_CLUSTER_MEMBERS = 1000;

type View = "photos" | "faces";
type Api = ReturnType<typeof useAuth>["api"];

function listUrl(view: View): string {
  return view === "faces" ? "/clusters/?view=faces" : "/clusters/";
}
function memberUrl(view: View, clusterId: number): string {
  return view === "faces" ? `/clusters/?view=faces&cluster=${clusterId}` : `/clusters/?cluster=${clusterId}`;
}

export default function ClustersPage() {
  return (
    <Suspense fallback={<section className="similar-page" />}>
      <ClustersPageInner />
    </Suspense>
  );
}

function ClustersPageInner() {
  const { api } = useAuth();
  const router = useRouter();
  const searchParams = useSearchParams();
  const view: View = searchParams.get("view") === "faces" ? "faces" : "photos";
  const clusterParam = searchParams.get("cluster");
  const clusterId = clusterParam !== null ? Number(clusterParam) : null;

  if (clusterId !== null && Number.isFinite(clusterId)) {
    return view === "faces" ? (
      <FaceClusterMembers clusterId={clusterId} onBack={() => router.push(listUrl("faces"))} api={api} />
    ) : (
      <ClusterMembers clusterId={clusterId} onBack={() => router.push(listUrl("photos"))} api={api} />
    );
  }
  return view === "faces" ? (
    <FaceClusterList api={api} view={view} onSwitchView={(v) => router.push(listUrl(v))} onOpen={(id) => router.push(memberUrl("faces", id))} />
  ) : (
    <ClusterList api={api} view={view} onSwitchView={(v) => router.push(listUrl(v))} onOpen={(id) => router.push(memberUrl("photos", id))} />
  );
}

function ViewToggle({ view, onChange }: { view: View; onChange: (v: View) => void }) {
  return (
    <div className="pf-size" role="group" aria-label="Cluster kind">
      <button type="button" className={view === "photos" ? "active" : ""} onClick={() => onChange("photos")}>
        Photos
      </button>
      <button type="button" className={view === "faces" ? "active" : ""} onClick={() => onChange("faces")}>
        Faces
      </button>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Photo clusters
// ---------------------------------------------------------------------------

function ClusterList({ api, view, onSwitchView, onOpen }: { api: Api; view: View; onSwitchView: (v: View) => void; onOpen: (id: number) => void }) {
  const [clusters, setClusters] = useState<MediaCluster[]>([]);
  const [status, setStatus] = useState<"loading" | "done" | "error">("loading");

  useEffect(() => {
    const ctrl = new AbortController();
    setClusters([]);
    setStatus("loading");
    api
      .mediasClusters((c) => setClusters((prev) => [...prev, c]), ctrl.signal)
      .then(() => setStatus("done"))
      .catch((err) => {
        if (ctrl.signal.aborted) return;
        console.error("clusters list failed", err);
        setStatus("error");
      });
    return () => ctrl.abort();
  }, [api]);

  const visible = useMemo(() => clusters.filter((c) => c.size > 1), [clusters]);
  const hidden = clusters.length - visible.length;
  const scrollRef = useScrollRestoration<HTMLDivElement>("clusters:photos:list");

  return (
    <section className="similar-page">
      <header className="similar-header">
        <div className="similar-title">
          <span className="similar-kicker">Clusters of similar photos</span>
          <ViewToggle view={view} onChange={onSwitchView} />
        </div>
        <div className="similar-status">
          {status === "loading" && <span className="mosaic-busy" aria-label="loading" />}
          <span>
            {visible.length} clusters
            {hidden > 0 && ` · ${hidden} singleton${hidden > 1 ? "s" : ""} hidden`}
          </span>
        </div>
      </header>

      {status === "error" && <div className="similar-empty">Couldn’t load clusters.</div>}
      {status === "done" && visible.length === 0 && (
        <div className="similar-empty">
          No clusters yet — run the <code> MediaFeaturesClustering </code> CLI to build them.
        </div>
      )}

      {visible.length > 0 && (
        <div className="similar-grid" ref={scrollRef}>
          {visible.map((c) => (
            <div
              key={c.clusterId}
              role="button"
              tabIndex={0}
              className="cluster-cover mosaic-tile"
              title={`Cluster ${c.clusterId} · ${c.size} photos`}
              onClick={() => onOpen(c.clusterId)}
              onKeyDown={(e) => {
                if (e.key === "Enter" || e.key === " ") {
                  e.preventDefault();
                  onOpen(c.clusterId);
                }
              }}
            >
              {c.coverAccessKey && (
                <img className="layer-mini" src={api.mediaMiniatureUrl(c.coverAccessKey)} loading="lazy" decoding="async" alt="" />
              )}
              <span className="cluster-badge">{c.size}</span>
            </div>
          ))}
        </div>
      )}
    </section>
  );
}

function ClusterMembers({ api, clusterId, onBack }: { api: Api; clusterId: number; onBack: () => void }) {
  const [members, setMembers] = useState<Media[]>([]);
  const [status, setStatus] = useState<"loading" | "done" | "error">("loading");
  const [truncated, setTruncated] = useState(false);
  const seenRef = useRef<Set<string>>(new Set());
  const truncatedRef = useRef(false);

  useEffect(() => {
    const ctrl = new AbortController();
    seenRef.current = new Set();
    truncatedRef.current = false;
    setMembers([]);
    setTruncated(false);
    setStatus("loading");
    api
      .mediaCluster(clusterId, {
        signal: ctrl.signal,
        onItem: (item) => {
          if (seenRef.current.has(item.accessKey)) return;
          // A cluster can run into the thousands - cap what actually gets fetched and rendered
          // rather than streaming (and mounting) every one of them.
          if (seenRef.current.size >= MAX_DISPLAYED_CLUSTER_MEMBERS) {
            truncatedRef.current = true;
            ctrl.abort();
            return;
          }
          seenRef.current.add(item.accessKey);
          setMembers((prev) => [...prev, item]);
        },
      })
      .then(() => setStatus("done"))
      .catch((err) => {
        if (truncatedRef.current) {
          setTruncated(true);
          setStatus("done");
          return;
        }
        if (ctrl.signal.aborted) return;
        console.error("cluster members failed", err);
        setStatus("error");
      });
    return () => ctrl.abort();
  }, [api, clusterId]);

  const scrollRef = useScrollRestoration<HTMLDivElement>(`clusters:photos:members:${clusterId}`);

  return (
    <section className="similar-page">
      <header className="similar-header">
        <div className="similar-title">
          <button type="button" className="similar-reference" onClick={onBack} title="Back to all clusters">
            ← all clusters
          </button>
          <span className="similar-kicker">Cluster {clusterId}</span>
        </div>
        <div className="similar-status">
          {status === "loading" && <span className="mosaic-busy" aria-label="loading" />}
          <span>
            {members.length} photos
            {truncated && ` · showing first ${MAX_DISPLAYED_CLUSTER_MEMBERS}`}
          </span>
        </div>
      </header>

      {status === "error" && <div className="similar-empty">Couldn’t load this cluster.</div>}
      {members.length > 0 && (
        <div className="similar-grid" ref={scrollRef}>
          {members.map((m, i) => (
            <MosaicTile key={m.accessKey} media={m} offset={i} />
          ))}
        </div>
      )}
    </section>
  );
}

// ---------------------------------------------------------------------------
// Face clusters — same shape as the photo clusters above, but grouping detected faces by
// face-embedding similarity. A cluster mixes faces at every identification stage - unidentified,
// merely inferred, already confirmed, even ones dismissed - so the members view surfaces that
// state and lets it be acted on directly, the same as the Persons tab's face grids do: a green
// name badge once confirmed (click to re-open FaceEditModal and change/remove it), an amber one
// while only inferred (click to confirm outright), and a grey "ignored" flag for faces someone
// already dismissed the inference on.
// ---------------------------------------------------------------------------

function FaceClusterList({ api, view, onSwitchView, onOpen }: { api: Api; view: View; onSwitchView: (v: View) => void; onOpen: (id: number) => void }) {
  const [clusters, setClusters] = useState<FaceCluster[]>([]);
  const [status, setStatus] = useState<"loading" | "done" | "error">("loading");
  const [hideConfirmed, setHideConfirmed] = useState(false);

  useEffect(() => {
    try {
      setHideConfirmed(sessionStorage.getItem("faceClustersList.hideConfirmed") === "1");
    } catch {
      /* ignore */
    }
  }, []);
  function persistHideConfirmed(v: boolean) {
    setHideConfirmed(v);
    try {
      sessionStorage.setItem("faceClustersList.hideConfirmed", v ? "1" : "0");
    } catch {
      /* ignore */
    }
  }

  useEffect(() => {
    const ctrl = new AbortController();
    setClusters([]);
    setStatus("loading");
    api
      .facesClusters((c) => setClusters((prev) => [...prev, c]), ctrl.signal)
      .then(() => setStatus("done"))
      .catch((err) => {
        if (ctrl.signal.aborted) return;
        console.error("face clusters list failed", err);
        setStatus("error");
      });
    return () => ctrl.abort();
  }, [api]);

  const grouped = useMemo(() => clusters.filter((c) => c.size > 1), [clusters]);
  const visible = useMemo(() => grouped.filter((c) => !hideConfirmed || c.confirmedCount < c.size), [grouped, hideConfirmed]);
  const singletonsHidden = clusters.length - grouped.length;
  const confirmedHidden = grouped.length - visible.length;
  // Folds `hideConfirmed` into the key: toggling it changes which clusters are on screen (can even
  // empty the grid out and back), so it needs its own scroll memory rather than reusing/stomping
  // on the unfiltered list's.
  const scrollRef = useScrollRestoration<HTMLDivElement>(`clusters:faces:list:${hideConfirmed ? "hide" : "all"}`);

  return (
    <section className="similar-page">
      <header className="similar-header">
        <div className="similar-title">
          <span className="similar-kicker">Clusters of similar faces</span>
          <ViewToggle view={view} onChange={onSwitchView} />
          <label className="pf-show-ignored">
            <input type="checkbox" checked={hideConfirmed} onChange={(e) => persistHideConfirmed(e.target.checked)} /> Hide fully confirmed
          </label>
        </div>
        <div className="similar-status">
          {status === "loading" && <span className="mosaic-busy" aria-label="loading" />}
          <span>
            {visible.length} clusters
            {singletonsHidden > 0 && ` · ${singletonsHidden} singleton${singletonsHidden > 1 ? "s" : ""} hidden`}
            {confirmedHidden > 0 && ` · ${confirmedHidden} fully confirmed hidden`}
          </span>
        </div>
      </header>

      {status === "error" && <div className="similar-empty">Couldn’t load face clusters.</div>}
      {status === "done" && visible.length === 0 && grouped.length === 0 && (
        <div className="similar-empty">
          No face clusters yet — run the <code> FaceFeaturesClustering </code> CLI to build them.
        </div>
      )}
      {status === "done" && visible.length === 0 && grouped.length > 0 && (
        <div className="similar-empty">Every face cluster is fully confirmed.</div>
      )}

      {visible.length > 0 && (
        <div className="similar-grid" ref={scrollRef}>
          {visible.map((c) => (
            <div
              key={c.clusterId}
              role="button"
              tabIndex={0}
              className="cluster-cover mosaic-tile"
              title={`Cluster ${c.clusterId} · ${c.confirmedCount}/${c.size} confirmed`}
              onClick={() => onOpen(c.clusterId)}
              onKeyDown={(e) => {
                if (e.key === "Enter" || e.key === " ") {
                  e.preventDefault();
                  onOpen(c.clusterId);
                }
              }}
            >
              {c.coverFaceId && <img className="layer-mini" src={api.faceImageUrl(c.coverFaceId)} loading="lazy" decoding="async" alt="" />}
              <span className={`cluster-badge${c.confirmedCount >= c.size ? " all-confirmed" : " pending"}`}>{c.size}</span>
            </div>
          ))}
        </div>
      )}
    </section>
  );
}

function FaceClusterMembers({ api, clusterId, onBack }: { api: Api; clusterId: number; onBack: () => void }) {
  const router = useRouter();
  const personsMap = usePersonsMap();
  const faceImageVersion = useFaceImageVersion();
  const setFacePerson = useSetFacePerson();
  const [members, setMembers] = useState<DetectedFace[]>([]);
  const [status, setStatus] = useState<"loading" | "done" | "error">("loading");
  const [truncated, setTruncated] = useState(false);
  const [editingFace, setEditingFace] = useState<DetectedFace | null>(null);
  const [hideConfirmed, setHideConfirmed] = useState(false);
  const seenRef = useRef<Set<string>>(new Set());
  const truncatedRef = useRef(false);

  useEffect(() => {
    try {
      setHideConfirmed(sessionStorage.getItem("faceClusterMembers.hideConfirmed") === "1");
    } catch {
      /* ignore */
    }
  }, []);
  function persistHideConfirmed(v: boolean) {
    setHideConfirmed(v);
    try {
      sessionStorage.setItem("faceClusterMembers.hideConfirmed", v ? "1" : "0");
    } catch {
      /* ignore */
    }
  }

  useEffect(() => {
    const ctrl = new AbortController();
    seenRef.current = new Set();
    truncatedRef.current = false;
    setMembers([]);
    setTruncated(false);
    setStatus("loading");
    api
      .faceCluster(clusterId, {
        signal: ctrl.signal,
        onItem: (item) => {
          if (seenRef.current.has(item.faceId)) return;
          // A cluster can run into the thousands - cap what actually gets fetched and rendered
          // rather than streaming (and mounting) every one of them.
          if (seenRef.current.size >= MAX_DISPLAYED_CLUSTER_MEMBERS) {
            truncatedRef.current = true;
            ctrl.abort();
            return;
          }
          seenRef.current.add(item.faceId);
          setMembers((prev) => [...prev, item]);
        },
      })
      .then(() => setStatus("done"))
      .catch((err) => {
        if (truncatedRef.current) {
          setTruncated(true);
          setStatus("done");
          return;
        }
        if (ctrl.signal.aborted) return;
        console.error("face cluster members failed", err);
        setStatus("error");
      });
    return () => ctrl.abort();
  }, [api, clusterId]);

  async function openFace(face: DetectedFace) {
    try {
      const state = await api.getState(face.originalId);
      if (!state.mediaAccessKey) {
        showError("Unable to resolve photo for this face");
        return;
      }
      router.push(`/?media=${encodeURIComponent(state.mediaAccessKey)}`);
    } catch {
      showError("Failed to open the viewer for this face");
    }
  }

  function personName(id: string | undefined | null): string {
    if (!id) return "";
    const p = personsMap.get(id);
    return p ? `${p.firstName || ""} ${p.lastName || ""}`.trim() : "";
  }

  async function confirmInferred(face: DetectedFace) {
    if (!face.inferredIdentifiedPersonId) return;
    try {
      await setFacePerson.mutateAsync({ faceId: face.faceId, personId: face.inferredIdentifiedPersonId });
    } catch {
      showError("Failed to confirm identification");
    }
  }

  // Counts always reflect every loaded face, whether or not the confirmed ones are currently
  // hidden from the grid below - hiding them shouldn't make the summary look like they're gone.
  const confirmedCount = useMemo(() => members.filter((f) => f.identifiedPersonId).length, [members]);
  const inferredCount = useMemo(() => members.filter((f) => !f.identifiedPersonId && f.inferredIdentifiedPersonId).length, [members]);
  const visibleMembers = useMemo(() => (hideConfirmed ? members.filter((f) => !f.identifiedPersonId) : members), [members, hideConfirmed]);
  // Same reasoning as the list's key: hiding confirmed faces can empty the grid out and back in,
  // so it gets independent scroll memory from the unfiltered view.
  const scrollRef = useScrollRestoration<HTMLDivElement>(`clusters:faces:members:${clusterId}:${hideConfirmed ? "hide" : "all"}`);

  return (
    <section className="similar-page">
      <header className="similar-header">
        <div className="similar-title">
          <button type="button" className="similar-reference" onClick={onBack} title="Back to all face clusters">
            ← all clusters
          </button>
          <span className="similar-kicker">Face cluster {clusterId}</span>
          <label className="pf-show-ignored">
            <input type="checkbox" checked={hideConfirmed} onChange={(e) => persistHideConfirmed(e.target.checked)} /> Hide confirmed
          </label>
        </div>
        <div className="similar-status">
          {status === "loading" && <span className="mosaic-busy" aria-label="loading" />}
          <span>
            {confirmedCount} confirmed · {inferredCount} inferred · {members.length} total
            {truncated && ` (showing first ${MAX_DISPLAYED_CLUSTER_MEMBERS})`}
          </span>
        </div>
      </header>

      {status === "error" && <div className="similar-empty">Couldn’t load this cluster.</div>}
      {status === "done" && members.length > 0 && visibleMembers.length === 0 && (
        <div className="similar-empty">Every loaded face here is already confirmed.</div>
      )}
      {visibleMembers.length > 0 && (
        <div className="similar-grid" ref={scrollRef}>
          {visibleMembers.map((f) => {
            const identifiedName = personName(f.identifiedPersonId);
            const showInferredBadge = !f.identifiedPersonId && !!f.inferredIdentifiedPersonId;
            const inferredName = showInferredBadge ? personName(f.inferredIdentifiedPersonId) : "";
            return (
              <div
                key={f.faceId}
                role="button"
                tabIndex={0}
                className={`face-tile${f.inferredIgnore ? " face-ignored" : ""}`}
                title="Open photo"
                onClick={(e) => {
                  if ((e.target as HTMLElement).closest("button")) return;
                  openFace(f);
                }}
                onKeyDown={(e) => {
                  if ((e.key === "Enter" || e.key === " ") && !(e.target as HTMLElement).closest("button")) {
                    e.preventDefault();
                    openFace(f);
                  }
                }}
              >
                <img className="face-img" src={api.faceImageUrl(f.faceId, faceImageVersion(f))} loading="lazy" decoding="async" alt="" />
                <button type="button" className="ft-edit" title="Edit identification" aria-label="Edit" onClick={() => setEditingFace(f)}>
                  ✎
                </button>
                {f.identifiedPersonId && (
                  <button type="button" className="face-badge identified" title="Click to edit identification" onClick={() => setEditingFace(f)}>
                    {identifiedName || "identified"}
                  </button>
                )}
                {showInferredBadge && (
                  <button type="button" className="face-badge inferred" title="Click to confirm identification" onClick={() => confirmInferred(f)}>
                    {inferredName || "inferred"}
                  </button>
                )}
                {f.inferredIgnore && (
                  <span className="face-badge ignored" title="Ignored — excluded from inference review">
                    ignored
                  </span>
                )}
              </div>
            );
          })}
        </div>
      )}

      {editingFace && (
        <FaceEditModal
          face={editingFace}
          onClose={() => setEditingFace(null)}
          onChanged={(updated) => setMembers((prev) => prev.map((f) => (f.faceId === updated.faceId ? updated : f)))}
          onDeleted={(faceId) => setMembers((prev) => prev.filter((f) => f.faceId !== faceId))}
        />
      )}
    </section>
  );
}
