"use client";

import { useQuery } from "@tanstack/react-query";
import { useAuth } from "../lib/keycloak-auth";
import type { MediaTimeline } from "../lib/api-client";

// Medias per page, and so per seek-table anchor. Also the largest number of items a single page
// request can pull, which the backend caps at 200. Bigger pages mean fewer round trips but a
// coarser rail and more wasted bytes when a fling only grazes a page.
export const MOSAIC_PAGE_SIZE = 100;

/**
 * The mosaic's seek table: exact media count plus one anchor key every MOSAIC_PAGE_SIZE medias.
 * Cheap on the backend (one index-only walk, no media records read) but not free, and the answer
 * only changes when medias are added or removed — hence the long stale time. It is deliberately
 * left out of the SSE invalidation table (see lib/live-events.tsx): a media arriving mid-scroll
 * would renumber every offset and yank the grid out from under the viewer.
 */
export function useMosaicTimeline() {
  const { api } = useAuth();
  return useQuery<MediaTimeline>({
    queryKey: ["mosaic", "timeline", MOSAIC_PAGE_SIZE],
    queryFn: ({ signal }) => api.mediasTimeline(MOSAIC_PAGE_SIZE, signal),
    staleTime: 10 * 60 * 1000,
    gcTime: 30 * 60 * 1000,
    refetchOnWindowFocus: false,
  });
}
