import type { MediaLocation } from "./api-client";

// The map tab's marker data (potentially thousands of NDJSON-streamed rows) is expensive to
// re-fetch, but the page itself unmounts and remounts on every navigation away and back — a plain
// per-component effect would re-stream the whole thing every single visit. Cache it here, at
// module scope, so a revisit reuses what's already in memory instead of hitting the network
// again. Invalidated (not incrementally updated) by LiveEventsProvider on "media"/"bag"/"sync"
// events — simpler and safer than trying to patch one row in place, at the cost of one extra
// refetch the next time the map is actually opened after something changed.
let cache: MediaLocation[] | null = null;

export function getCachedMapLocations(): MediaLocation[] | null {
  return cache;
}

export function setCachedMapLocations(locations: MediaLocation[]): void {
  cache = locations;
}

export function clearMapCache(): void {
  cache = null;
}
