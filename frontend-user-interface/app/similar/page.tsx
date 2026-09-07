"use client";

// Similar-photos view — a flat grid of the photos most visually alike a reference one,
// best match first. Launched from the Viewer's "≈ Similar" action (and reachable from
// anywhere as /similar/?media=<accessKey>).
//
// Results are capped server-side (<= 100), so unlike the Mosaic this needs no
// virtualization: stream them in once and render a plain responsive grid of the same
// MosaicTile the Mosaic uses.

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useEffect, useMemo, useRef, useState } from "react";
import { MosaicTile } from "../../components/MosaicTile";
import { useAuth } from "../../lib/keycloak-auth";
import type { Media } from "../../lib/api-client";

const RESULT_COUNT = 60;

export default function SimilarPage() {
  return (
    <Suspense fallback={<section className="similar-page" />}>
      <SimilarPageInner />
    </Suspense>
  );
}

function SimilarPageInner() {
  const { api } = useAuth();
  const router = useRouter();
  const searchParams = useSearchParams();
  const mediaKey = searchParams.get("media");

  const [reference, setReference] = useState<Media | null>(null);
  const [results, setResults] = useState<Media[]>([]);
  const [status, setStatus] = useState<"idle" | "loading" | "done" | "error">("idle");
  const seenRef = useRef<Set<string>>(new Set());

  useEffect(() => {
    if (!mediaKey) {
      setStatus("idle");
      return;
    }
    const ctrl = new AbortController();
    seenRef.current = new Set();
    setResults([]);
    setReference(null);
    setStatus("loading");

    api
      .getMediaByKey(mediaKey)
      .then((m) => setReference(m))
      .catch(() => {
        /* the grid still works without the reference header */
      });

    api
      .mediaSimilar(mediaKey, {
        count: RESULT_COUNT,
        signal: ctrl.signal,
        onItem: (item) => {
          if (seenRef.current.has(item.accessKey)) return;
          seenRef.current.add(item.accessKey);
          setResults((prev) => [...prev, item]);
        },
      })
      .then(() => setStatus("done"))
      .catch((err) => {
        if (ctrl.signal.aborted) return;
        console.error("similar photos stream failed", err);
        setStatus("error");
      });

    return () => ctrl.abort();
  }, [api, mediaKey]);

  const referenceThumb = useMemo(
    () => (reference ? api.mediaMiniatureUrl(reference.accessKey) : null),
    [api, reference]
  );

  return (
    <section className="similar-page">
      <header className="similar-header">
        <div className="similar-title">
          <span className="similar-kicker">≈ Similar photos</span>
          {reference && (
            <button
              type="button"
              className="similar-reference"
              title="Back to this photo in the Viewer"
              onClick={() => router.push(`/?media=${encodeURIComponent(reference.accessKey)}`)}
            >
              {referenceThumb && <img src={referenceThumb} alt="" />}
              <span>reference photo</span>
            </button>
          )}
        </div>
        <div className="similar-status">
          {status === "loading" && <span className="mosaic-busy" aria-label="loading" />}
          {status !== "idle" && <span>{results.length} shown</span>}
          <Link href="/mosaic/">Back to Mosaic</Link>
        </div>
      </header>

      {!mediaKey && <div className="similar-empty">Open a photo in the Viewer and pick “≈ Similar”.</div>}

      {mediaKey && status === "error" && (
        <div className="similar-empty">Couldn’t load similar photos. The feature vectors may not be computed yet.</div>
      )}

      {mediaKey && status !== "error" && results.length === 0 && status === "done" && (
        <div className="similar-empty">
          No similar photos found. This photo may not have a feature vector yet — run the
          <code> ComputeMediaFeatures </code> backfill or re-synchronize.
        </div>
      )}

      {results.length > 0 && (
        <div className="similar-grid">
          {results.map((m, i) => (
            <MosaicTile key={m.accessKey} media={m} offset={i} />
          ))}
        </div>
      )}
    </section>
  );
}
