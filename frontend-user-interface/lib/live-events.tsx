"use client";

// Subscribes once to the SSE bus (lib/sse.ts) and invalidates the React Query cache entries a
// given event could affect, so every open tab picks up create/update/delete without polling or a
// manual reload. Deliberately narrow: this drives simple entity lists/details only. The mosaic
// and map views manage their own bespoke streamed/clustered state (hooks/useMosaicFeed.ts,
// hooks/useMapMarkers.ts) and are not force-reflowed here — invalidating mid-scroll would yank
// content out from under the viewer, which is worse than a slightly stale tile.
import { useQueryClient, type QueryKey } from "@tanstack/react-query";
import { useEffect } from "react";
import { useAuth } from "./keycloak-auth";
import { clearMapCache } from "./map-cache";
import { connectEvents, type ApiEvent } from "./sse";

// The map tab's marker cache (lib/map-cache.ts) isn't React Query state, so it can't be
// invalidated by key — any event that could add/move/remove a geotagged marker just drops the
// whole cache; the map only pays for a refetch the next time it's actually opened.
function invalidatesMapCache(entity: ApiEvent["entity"]): boolean {
  return entity === "media" || entity === "bag" || entity === "sync";
}

function queryKeysForEvent({ entity, id }: ApiEvent): QueryKey[] {
  switch (entity) {
    case "owner":
      return id ? [["owners"], ["owner", id]] : [["owners"]];
    case "store":
      return id ? [["stores"], ["store", id]] : [["stores"]];
    case "person":
      return id ? [["persons"], ["person", id]] : [["persons"]];
    case "portfolio":
      return id ? [["portfolios"], ["portfolio", id]] : [["portfolios"]];
    case "bag":
      return id ? [["bags"], ["bag", id]] : [["bags"]];
    case "media":
      return id ? [["media", id]] : [];
    case "face":
      return id ? [["faces"], ["face", id], ["personFaces"]] : [["faces"], ["personFaces"]];
    case "sync":
      return [["syncStatus"]];
    default:
      return [];
  }
}

export function LiveEventsProvider({ children }: { children: React.ReactNode }) {
  const { ready, getToken } = useAuth();
  const queryClient = useQueryClient();

  useEffect(() => {
    if (!ready) return;
    return connectEvents(getToken, (event) => {
      for (const queryKey of queryKeysForEvent(event)) {
        queryClient.invalidateQueries({ queryKey });
      }
      if (invalidatesMapCache(event.entity)) clearMapCache();
    });
  }, [ready, getToken, queryClient]);

  return <>{children}</>;
}
