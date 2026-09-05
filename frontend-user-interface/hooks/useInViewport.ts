"use client";

import { useEffect, useRef, useState, type RefObject } from "react";

// One-shot "has this element scrolled near the viewport" gate — used to defer a list tile's data
// fetch (not just its <img loading="lazy">) until it's actually about to be seen. Without this, a
// list of hundreds/thousands of tiles (e.g. the Bags list) fires that many originalId->accessKey
// resolutions at once and the browser runs out of connections (ERR_INSUFFICIENT_RESOURCES) — the
// exact problem the previous app's per-tab IntersectionObserver+concurrency-queue code worked
// around; this hook is the one shared replacement for all of them.
export function useInViewport<T extends HTMLElement>(rootMargin = "200px"): [RefObject<T | null>, boolean] {
  const ref = useRef<T>(null);
  const [inView, setInView] = useState(false);

  useEffect(() => {
    if (inView) return;
    const el = ref.current;
    if (!el) return;
    if (typeof IntersectionObserver === "undefined") {
      setInView(true);
      return;
    }
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((e) => e.isIntersecting)) {
          setInView(true);
          observer.disconnect();
        }
      },
      { rootMargin }
    );
    observer.observe(el);
    return () => observer.disconnect();
  }, [inView, rootMargin]);

  return [ref, inView];
}
