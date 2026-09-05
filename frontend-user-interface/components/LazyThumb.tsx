"use client";

// Shared list-tile thumbnail: resolves an Original's mediaAccessKey and shows its miniature, with
// a "No image" placeholder until resolved. Used by owners/stores/persons/events list tiles alike
// (one implementation instead of one per tab). The originalId->accessKey *lookup* is gated behind
// useInViewport (not just the <img loading="lazy">) — a list of hundreds/thousands of tiles would
// otherwise fire that many lookups at once and exhaust the browser's connections, the exact
// problem the previous app's per-tab IntersectionObserver+concurrency-queue code existed for.
import { useAuth } from "../lib/keycloak-auth";
import { useInViewport } from "../hooks/useInViewport";
import { useMediaAccessKey } from "../hooks/useMediaAccessKey";

export function LazyThumb({
  originalId,
  alt,
  small,
  badge,
  variant = "miniature",
  className: extraClassName,
}: {
  originalId?: string | null;
  alt: string;
  small?: boolean;
  badge?: string;
  variant?: "miniature" | "normalized";
  className?: string;
}) {
  const { api } = useAuth();
  const [ref, inView] = useInViewport<HTMLDivElement>();
  const accessKey = useMediaAccessKey(inView ? originalId : null);
  const className = `list-thumb${small ? " list-thumb-sm" : ""}${extraClassName ? ` ${extraClassName}` : ""}`;

  if (!originalId) return <div ref={ref} className={className}>No image</div>;
  if (!accessKey) return <div ref={ref} className={className} />;

  const src = variant === "normalized" ? api.mediaNormalizedUrl(accessKey) : api.mediaMiniatureUrl(accessKey);
  return (
    <div ref={ref} className={className}>
      <img src={src} alt={alt} loading="lazy" decoding="async" />
      {badge && <span className="icon-badge">{badge}</span>}
    </div>
  );
}
