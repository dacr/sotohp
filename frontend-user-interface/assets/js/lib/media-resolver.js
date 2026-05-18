/**
 * Shared cache for `originalId → state.mediaAccessKey` lookups.
 *
 * Many tabs (events, portfolios, owners, persons, mosaic) need to resolve an
 * originalId to a mediaAccessKey before they can build an image URL. Each used
 * to keep its own `const stateCache = new Map()` — this module replaces all of
 * them with a single shared cache that survives navigation between tabs.
 *
 *   import { resolveMediaAccessKey } from './lib/media-resolver.js';
 *   const key = await resolveMediaAccessKey(api, originalId);
 *   if (!key) return; // state missing / network failure
 *   img.src = api.mediaMiniatureUrl(key);
 *
 * Failures are not cached — a later call retries.
 */

const stateCache = new Map(); // originalId -> Promise<State|null>

/**
 * Resolve the full state for an originalId, caching the lookup.
 * Returns Promise<State|null>. Rejected promises are evicted from the cache
 * so subsequent callers retry instead of receiving the cached failure.
 */
export function resolveState(api, originalId) {
  if (!originalId) return Promise.resolve(null);
  let p = stateCache.get(originalId);
  if (!p) {
    p = api.getState(originalId).catch((err) => {
      stateCache.delete(originalId);
      throw err;
    });
    stateCache.set(originalId, p);
  }
  return p;
}

/**
 * Resolve to the mediaAccessKey string, or `null` if the lookup fails or
 * the original has no associated media.
 */
export async function resolveMediaAccessKey(api, originalId) {
  try {
    const state = await resolveState(api, originalId);
    return state && state.mediaAccessKey ? state.mediaAccessKey : null;
  } catch {
    return null;
  }
}

/** Drop the cache — call after a sync run, on logout, or for testing. */
export function clearMediaResolverCache() {
  stateCache.clear();
}
