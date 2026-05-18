/**
 * Shared cache of `ownerId → "firstName lastName"` used by the stores tab
 * for thumbnail labels. Invalidated by the owners tab after create/edit.
 *
 *   const map = await getOwnersMap(api);
 *   clearOwnersMap();   // call after editing/creating an owner
 */

let cache = null; // Map<ownerId, string> | null

export function clearOwnersMap() { cache = null; }

export async function getOwnersMap(api) {
  if (cache) return cache;
  try {
    const owners = await api.listOwners();
    const map = new Map();
    for (const o of owners) map.set(o.id, `${o.firstName} ${o.lastName}`);
    cache = map;
  } catch {
    cache = new Map();
  }
  return cache;
}
