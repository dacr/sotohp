"use client";

// Subscribes once to the SSE bus (lib/sse.ts) and invalidates the React Query cache entries a
// given event could affect, so every open tab picks up create/update/delete without polling or a
// manual reload. Deliberately narrow: this drives simple entity lists/details only. The mosaic
// and map views manage their own bespoke streamed/clustered state (hooks/useMosaicPages.ts,
// hooks/useMapMarkers.ts) and are not force-reflowed here — invalidating mid-scroll would yank
// content out from under the viewer, which is worse than a slightly stale tile.
import { useQueryClient, type QueryKey } from "@tanstack/react-query";
import { useEffect } from "react";
import { syncFaceFromServer } from "../hooks/useFaces";
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
      // An identified `id` is handled face-by-face below; only a bus frame that names no face at
      // all is broad enough to justify reloading the (very large) whole-collection list.
      return id ? [] : [["faces"], ["personFaces"]];
    case "sync":
      return [["syncStatus"]];
    default:
      return [];
  }
}

export function LiveEventsProvider({ children }: { children: React.ReactNode }) {
  const { ready, getToken, api } = useAuth();
  const queryClient = useQueryClient();

  useEffect(() => {
    if (!ready) return;
    return connectEvents(getToken, (event) => {
      // Faces are the one entity whose list is far too big to reload per event — reconcile the
      // single face the frame names instead (hooks/useFaces.ts).
      if (event.entity === "face" && event.id) void syncFaceFromServer(queryClient, api, event.id, event.action);
      for (const queryKey of queryKeysForEvent(event)) {
        queryClient.invalidateQueries({ queryKey });
      }
      if (invalidatesMapCache(event.entity)) clearMapCache();
    });
  }, [ready, getToken, api, queryClient]);

  return <>{children}</>;
}
