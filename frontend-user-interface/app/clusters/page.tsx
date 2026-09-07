"use client";

// Clusters tab — groups of visually similar photos.
//
// Two views in one route:
//   - no `?cluster=`  : a grid of cluster covers (one tile per cluster, badged with its size)
//   - `?cluster=<id>` : the photos inside that cluster, as a plain grid of MosaicTiles
//
// The cluster assignments are produced offline by the `MediaFeaturesClustering` CLI; until it
// has run the list is empty.

import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useEffect, useMemo, useRef, useState } from "react";
import { MosaicTile } from "../../components/MosaicTile";
import { useAuth } from "../../lib/keycloak-auth";
import type { Media, MediaCluster } from "../../lib/api-client";

// Clusters bigger than this are hidden from the list: at loose DBSCAN settings a single
// mega-cluster can chain together most of the library, which is noise rather than a group.
const MAX_LISTED_CLUSTER_SIZE = 1000;

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
  const clusterParam = searchParams.get("cluster");
  const clusterId = clusterParam !== null ? Number(clusterParam) : null;

  if (clusterId !== null && Number.isFinite(clusterId)) {
    return <ClusterMembers clusterId={clusterId} onBack={() => router.push("/clusters/")} api={api} />;
  }
  return <ClusterList onOpen={(id) => router.push(`/clusters/?cluster=${id}`)} api={api} />;
}

type Api = ReturnType<typeof useAuth>["api"];

function ClusterList({ api, onOpen }: { api: Api; onOpen: (id: number) => void }) {
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

  const visible = useMemo(() => clusters.filter((c) => c.size <= MAX_LISTED_CLUSTER_SIZE), [clusters]);
  const hidden = clusters.length - visible.length;

  return (
    <section className="similar-page">
      <header className="similar-header">
        <div className="similar-title">
          <span className="similar-kicker">Clusters of similar photos</span>
        </div>
        <div className="similar-status">
          {status === "loading" && <span className="mosaic-busy" aria-label="loading" />}
          <span>
            {visible.length} clusters
            {hidden > 0 && ` · ${hidden} over ${MAX_LISTED_CLUSTER_SIZE} hidden`}
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
        <div className="similar-grid">
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
  const seenRef = useRef<Set<string>>(new Set());

  useEffect(() => {
    const ctrl = new AbortController();
    seenRef.current = new Set();
    setMembers([]);
    setStatus("loading");
    api
      .mediaCluster(clusterId, {
        signal: ctrl.signal,
        onItem: (item) => {
          if (seenRef.current.has(item.accessKey)) return;
          seenRef.current.add(item.accessKey);
          setMembers((prev) => [...prev, item]);
        },
      })
      .then(() => setStatus("done"))
      .catch((err) => {
        if (ctrl.signal.aborted) return;
        console.error("cluster members failed", err);
        setStatus("error");
      });
    return () => ctrl.abort();
  }, [api, clusterId]);

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
          <span>{members.length} photos</span>
        </div>
      </header>

      {status === "error" && <div className="similar-empty">Couldn’t load this cluster.</div>}
      {members.length > 0 && (
        <div className="similar-grid">
          {members.map((m, i) => (
            <MosaicTile key={m.accessKey} media={m} offset={i} />
          ))}
        </div>
      )}
    </section>
  );
}
