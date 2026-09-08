"use client";

// Search tab — a Google-style text box that runs a free-text query against the
// OpenSearch/Elasticsearch index (populated by the API's live publish path and the
// `SearchReindex` CLI). Matches come back over NDJSON already ranked by relevance, then
// most recent first, and are rendered as a compact grid of the same MosaicTile the Mosaic
// and Similar views use.
//
// The active query lives in the URL (`?q=`) so a search is shareable, reloadable and
// remembered by the nav's per-tab memory (NavHeader restores `/search/?q=…` on tab re-entry).
//
// Leaving the tab unmounts this component, so the fetched results and scroll position would be
// lost and re-fetched on return. `lastSearch` — module scope, so it outlives the unmount for the
// life of the SPA session — caches them: coming back to the same query rehydrates instantly with
// no request. A full page reload still re-fetches (the cache is gone), which is fine.

import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useCallback, useEffect, useRef, useState } from "react";
import { MosaicTile } from "../../components/MosaicTile";
import { useAuth } from "../../lib/keycloak-auth";
import type { Media } from "../../lib/api-client";

const RESULT_COUNT = 1000;

type CachedSearch = { query: string; results: Media[]; status: "done" | "error"; scrollTop: number };
let lastSearch: CachedSearch | null = null;

export default function SearchPage() {
  return (
    <Suspense fallback={<section className="search-page" />}>
      <SearchPageInner />
    </Suspense>
  );
}

function SearchPageInner() {
  const { api } = useAuth();
  const router = useRouter();
  const searchParams = useSearchParams();
  const urlQuery = searchParams.get("q") ?? "";

  const restorable = lastSearch && lastSearch.query === urlQuery.trim() && urlQuery.trim() !== "" ? lastSearch : null;
  const [text, setText] = useState(urlQuery);
  const [results, setResults] = useState<Media[]>(restorable ? restorable.results : []);
  const [status, setStatus] = useState<"idle" | "loading" | "done" | "error">(restorable ? restorable.status : "idle");
  const seenRef = useRef<Set<string>>(new Set(restorable ? restorable.results.map((m) => m.accessKey) : []));
  const inputRef = useRef<HTMLInputElement>(null);
  const abortRef = useRef<AbortController | null>(null);
  const gridRef = useRef<HTMLDivElement>(null);
  // Which query the current `results` belong to, so the URL effect can tell "already loaded / just
  // rehydrated" from "need to fetch".
  const loadedQueryRef = useRef<string | null>(restorable ? restorable.query : null);
  // Set once, on a mount that rehydrated from the cache, so the scroll position is restored after
  // the grid paints rather than on every render.
  const pendingScrollRef = useRef<number | null>(restorable ? restorable.scrollTop : null);

  // Keep the text box in sync when the query changes from outside (back/forward, a shared link).
  useEffect(() => {
    setText(urlQuery);
  }, [urlQuery]);

  useEffect(() => {
    inputRef.current?.focus();
  }, []);

  const runSearch = useCallback(
    (query: string) => {
      abortRef.current?.abort();
      const ctrl = new AbortController();
      abortRef.current = ctrl;
      seenRef.current = new Set();
      loadedQueryRef.current = query; // results below are (being) built for this query
      pendingScrollRef.current = null;
      setResults([]);
      setStatus("loading");
      api
        .mediaSearch(query, {
          count: RESULT_COUNT,
          signal: ctrl.signal,
          onItem: (item) => {
            if (seenRef.current.has(item.accessKey)) return;
            seenRef.current.add(item.accessKey);
            setResults((prev) => [...prev, item]);
          },
        })
        .then(() => {
          if (!ctrl.signal.aborted) setStatus("done");
        })
        .catch((err) => {
          if (ctrl.signal.aborted) return;
          console.error("search stream failed", err);
          setStatus("error");
        });
    },
    [api]
  );

  // The URL is the single source of truth for what is being searched: submitting just pushes
  // `?q=`, and this effect fires the actual request whenever that value settles — unless the
  // results for that exact query are already on screen (fresh fetch, or rehydrated from the
  // module-level cache when re-entering the tab).
  useEffect(() => {
    const query = urlQuery.trim();
    if (!query) {
      abortRef.current?.abort();
      loadedQueryRef.current = null;
      lastSearch = null;
      setResults([]);
      setStatus("idle");
      return;
    }
    if (loadedQueryRef.current === query) return;
    runSearch(query);
    return () => abortRef.current?.abort();
  }, [urlQuery, runSearch]);

  // Persist the completed search so leaving and re-entering the tab restores it without a refetch.
  useEffect(() => {
    const query = urlQuery.trim();
    if (!query || (status !== "done" && status !== "error")) return;
    lastSearch = { query, results, status, scrollTop: gridRef.current?.scrollTop ?? 0 };
  }, [urlQuery, results, status]);

  // Track the scroll offset live so the value cached on unmount is current.
  function onGridScroll(e: React.UIEvent<HTMLDivElement>) {
    if (lastSearch && lastSearch.query === urlQuery.trim()) lastSearch.scrollTop = e.currentTarget.scrollTop;
  }

  // Restore the scroll position once, after the rehydrated grid has painted.
  useEffect(() => {
    if (pendingScrollRef.current != null && gridRef.current) {
      gridRef.current.scrollTop = pendingScrollRef.current;
      pendingScrollRef.current = null;
    }
  }, [results]);

  function clearSearch() {
    setText("");
    abortRef.current?.abort();
    loadedQueryRef.current = null;
    lastSearch = null;
    setResults([]);
    setStatus("idle");
    if (urlQuery) router.push("/search/");
    inputRef.current?.focus();
  }

  function submit(e: React.FormEvent) {
    e.preventDefault();
    const query = text.trim();
    const current = urlQuery.trim();
    if (query && query === current) {
      // Same query — re-run it explicitly (the URL effect won't refire on an unchanged value).
      runSearch(query);
      return;
    }
    router.push(query ? `/search/?q=${encodeURIComponent(query)}` : "/search/");
  }

  const hasSearched = status !== "idle";

  return (
    <section className="search-page">
      <div className="search-hero">
        <form className="search-box" onSubmit={submit} role="search">
          <span className="search-icon" aria-hidden>
            🔍
          </span>
          <input
            ref={inputRef}
            type="text"
            className="search-input"
            placeholder="Search your photos…"
            value={text}
            onChange={(e) => setText(e.target.value)}
            autoComplete="off"
            autoCapitalize="off"
            spellCheck={false}
            aria-label="Search query"
          />
          {text && (
            <button type="button" className="search-clear" title="Clear" aria-label="Clear" onClick={clearSearch}>
              ✕
            </button>
          )}
          {/* No visible submit button: a single-field form submits on Enter. */}
        </form>
        <div className="search-status">
          {status === "loading" && <span className="mosaic-busy" aria-label="loading" />}
          {hasSearched && status !== "error" && (
            <span>
              {results.length} result{results.length === 1 ? "" : "s"}
              {status === "loading" ? "…" : ""}
            </span>
          )}
          {status === "error" && <span className="search-error">Search failed — the index may be unavailable.</span>}
        </div>
      </div>

      {status === "done" && results.length === 0 && (
        <div className="search-empty">
          No photos matched “{urlQuery.trim()}”. Try other words — descriptions, keywords, places, camera, identified
          people and detected objects are all searched, and every word has to match.
        </div>
      )}

      {results.length > 0 && (
        <div className="search-grid" ref={gridRef} onScroll={onGridScroll}>
          {results.map((m, i) => (
            <MosaicTile key={m.accessKey} media={m} offset={i} />
          ))}
        </div>
      )}
    </section>
  );
}
