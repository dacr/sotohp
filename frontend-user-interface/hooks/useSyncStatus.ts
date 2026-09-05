"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef } from "react";
import { useAuth } from "../lib/keycloak-auth";

// The backend now broadcasts a "sync" SSE event about once a second while a run is active (see
// ApiApp.scala's syncStatusBroadcaster) — LiveEventsProvider turns that into a refetch of this
// query, which is the primary way this stays live. The refetchInterval below is just a fallback
// for a dropped/reconnecting SSE connection, not the main channel, hence the long period.
export function useSyncStatus() {
  const { api } = useAuth();
  const queryClient = useQueryClient();
  const wasRunning = useRef(false);

  const query = useQuery({
    queryKey: ["syncStatus"],
    queryFn: () => api.synchronizeStatus(),
    refetchInterval: (q) => (q.state.data?.running ? 15_000 : false),
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

  return { status: query.data, start: startMutation.mutateAsync, starting: startMutation.isPending };
}
