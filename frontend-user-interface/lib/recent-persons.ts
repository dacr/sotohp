// LRU of recently-identified person ids, purely client-side (localStorage) — feeds the "Recent"
// quick-pick pills in the face-identify modal. No server persistence, mirrors the previous app.
const KEY = "viewer.recentPersons";
const MAX = 10;

export function getRecentPersonIds(): string[] {
  try {
    const raw = localStorage.getItem(KEY);
    return raw ? JSON.parse(raw) : [];
  } catch {
    return [];
  }
}

export function pushRecentPersonId(id: string) {
  try {
    const ids = getRecentPersonIds().filter((x) => x !== id);
    ids.unshift(id);
    localStorage.setItem(KEY, JSON.stringify(ids.slice(0, MAX)));
  } catch {
    /* best-effort */
  }
}
