"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef } from "react";
import { useAuth } from "../lib/keycloak-auth";

// The status endpoint is admin only : a refusal means "not for this user" (the header pill then
// hides itself), anything else means the server can't be reached.
function isRefused(error: unknown): boolean {
  return error instanceof Error && /status: (401|403)\b/.test(error.message);
}

// The backend broadcasts a "sync" SSE event about once a second while a run is active (see
// ApiApp.scala's syncStatusBroadcaster) — LiveEventsProvider turns that into a refetch of this
// query, which is the primary way this stays live. The refetchInterval below is just a fallback
// for a dropped/reconnecting SSE connection, or for a server that went away, not the main channel.
// Mounted once in the header (components/SyncStatusPill.tsx), so it follows a run from any tab.
export function useSyncStatus() {
  const { api } = useAuth();
  const queryClient = useQueryClient();
  const wasRunning = useRef(false);

  const query = useQuery({
    queryKey: ["syncStatus"],
    queryFn: () => api.synchronizeStatus(),
    retry: (count, error) => !isRefused(error) && count < 2,
    refetchInterval: (q) => (q.state.data?.running || (q.state.error && !isRefused(q.state.error)) ? 15_000 : false),
  });

  useEffect(() => {
    const running = !!query.data?.running;
    if (wasRunning.current && !running) {
      // A run just finished — drop cached original->accessKey resolutions so newly indexed
      // media show up without a manual reload.
      queryClient.invalidateQueries({ queryKey: ["state"] });
    }
    wasRunning.current = running;
  }, [query.data?.running, queryClient]);

  const startMutation = useMutation({
    mutationFn: (days: number | undefined) => api.synchronizeStart(days),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["syncStatus"] }),
  });

  const stopMutation = useMutation({
    mutationFn: () => api.synchronizeStop(),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["syncStatus"] }),
  });

  return {
    status: query.data,
    refused: isRefused(query.error),
    unreachable: !!query.error && !isRefused(query.error),
    start: startMutation.mutateAsync,
    starting: startMutation.isPending,
    stop: stopMutation.mutateAsync,
    stopping: stopMutation.isPending,
  };
}
