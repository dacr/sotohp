"use client";

import { useQuery } from "@tanstack/react-query";
import { useAuth } from "../lib/keycloak-auth";

// originalId -> mediaAccessKey resolution, cached per id (replaces the old lib/media-resolver.js
// module-level Map — React Query's cache already is that shared cache, one entry per id, reused
// by every component that asks). Invalidated broadly when a sync run finishes (useSyncStatus).
export function useMediaAccessKey(originalId: string | undefined | null): string | null {
  const { api } = useAuth();
  const query = useQuery({
    queryKey: ["state", originalId],
    queryFn: async () => {
      const state = await api.getState(originalId!);
      return state.mediaAccessKey ?? null;
    },
    enabled: !!originalId,
    staleTime: 60_000,
  });
  return query.data ?? null;
}
