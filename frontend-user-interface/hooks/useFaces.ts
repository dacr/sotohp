"use client";

// Face lists and the mutations that change them.
//
// The lists are big — ["faces"] is the entire face collection (hundreds of thousands of entries,
// seconds to stream) — so a mutation must NOT hide its effect behind a full refetch. Two problems
// come with that: the refetch storm (one multi-megabyte reload per confirmed face), and the fact
// that anything relying on component-local "already handled" state to bridge the gap loses that
// state the moment the view unmounts. That is what made confirmed faces reappear in the inferred
// queue after switching tabs and coming back: the queue had been filtered locally, while the
// shared cache still held the pre-confirm list.
//
// So every mutation splices its own outcome into the cached lists instead, and the SSE bus
// (lib/live-events.tsx) reconciles against the server face by face, in batches. The lists still refetch
// in full on mount once stale, which is the periodic sanity check.
import { useMutation, useQuery, useQueryClient, type QueryClient, type QueryKey } from "@tanstack/react-query";
import { useAuth } from "../lib/keycloak-auth";
import { faceBoxVersion, type ApiClient, type DetectedFace } from "../lib/api-client";

const ALL_FACES_KEY = ["faces"];
const PERSON_FACES_KEY = ["personFaces"];
const FACE_CONTENT_REVISION_KEY = ["faceContentRevision"];

export function useAllFaces() {
  const { api } = useAuth();
  return useQuery({ queryKey: ALL_FACES_KEY, queryFn: ({ signal }) => api.listFaces(signal) });
}

export function usePersonFaces(personId: string | undefined | null) {
  const { api } = useAuth();
  return useQuery({
    queryKey: [...PERSON_FACES_KEY, personId],
    queryFn: async ({ signal }) => {
      const faces = await api.listPersonFaces(personId!, signal);
      return sortByTimestampDesc(faces);
    },
    enabled: !!personId,
  });
}

function sortByTimestampDesc(faces: DetectedFace[]): DetectedFace[] {
  return [...faces].sort((a, b) => new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime());
}

// A face's cached crop file gets rewritten in place - re-cut when the media is rotated, or repaired
// by the CLI's FaceOrientationAudit - under a faceId, and so a URL, that never changes. Usually the
// box moves with it, and faceBoxVersion() alone is enough to bust the browser cache (it is derived
// from the data, so it survives a reload). A *re-crop* is the exception : it fixes a crop that was
// cut against the wrong rotation and deliberately leaves the box exactly as it is, so the URL would
// stay identical across a repair that changed every pixel. This counter, bumped whenever the server
// tells us a face changed, covers that case.
function bumpFaceContentRevisions(qc: QueryClient, faceIds: string[]) {
  qc.setQueryData<Record<string, number>>(FACE_CONTENT_REVISION_KEY, (old) => {
    const next = { ...(old ?? {}) };
    for (const faceId of faceIds) next[faceId] = (next[faceId] ?? 0) + 1;
    return next;
  });
}

/** Builds the cache-busting token to hand to `api.faceImageUrl` for a given face. */
export function useFaceImageVersion(): (face: Pick<DetectedFace, "faceId" | "box">) => string {
  const { data } = useQuery<Record<string, number>>({
    queryKey: FACE_CONTENT_REVISION_KEY,
    queryFn: () => ({}),
    initialData: {},
    staleTime: Infinity,
    gcTime: Infinity,
  });
  return (face) => {
    const revision = data[face.faceId];
    return revision ? `${faceBoxVersion(face)}~${revision}` : faceBoxVersion(face);
  };
}

/** Same, for a face we only know by id (a person's cover face) - no box to fold in. */
export function useFaceIdImageVersion(): (faceId: string) => string | undefined {
  const { data } = useQuery<Record<string, number>>({
    queryKey: FACE_CONTENT_REVISION_KEY,
    queryFn: () => ({}),
    initialData: {},
    staleTime: Infinity,
    gcTime: Infinity,
  });
  return (faceId) => {
    const revision = data[faceId];
    return revision ? `~${revision}` : undefined;
  };
}

// Which person a face is listed under server-side — MediaServiceLive's faceIdByPersonId index keys
// on coalesce(identifiedPersonId, inferredIdentifiedPersonId), so /api/person/{id}/faces returns
// both the confirmed and the merely inferred ones.
function ownerOf(face: DetectedFace): string | undefined {
  return face.identifiedPersonId || face.inferredIdentifiedPersonId || undefined;
}

function isAllFacesKey(key: QueryKey): boolean {
  return key.length === 1 && key[0] === "faces";
}

function personFacesKeyOwner(key: QueryKey): string | null {
  return key.length === 2 && key[0] === "personFaces" ? (key[1] as string) : null;
}

// A refetch that started before the mutation carries pre-mutation data and would undo the splice
// below when it lands, so drop it — the patch is authoritative for the face it touches. Lists
// still loading for the first time are left alone: cancelling those would strand the view empty
// with nothing to retrigger the load, and a list with no data yet has no visible face to act on.
function cancelLoadedFaceListFetches(qc: QueryClient) {
  const stale = qc
    .getQueryCache()
    .findAll({ predicate: (q) => (isAllFacesKey(q.queryKey) || personFacesKeyOwner(q.queryKey) !== null) && q.state.data !== undefined && q.state.fetchStatus === "fetching" });
  for (const query of stale) void qc.cancelQueries({ queryKey: query.queryKey, exact: true });
}

// Everything below works on a *batch* of faces. A multi-selection can hold hundreds of faces, and
// ["faces"] hundreds of thousands: touching the cache once per face meant one full-list copy, one
// re-filter/re-sort of the view built on it and one grid re-render per face — minutes of watching
// tiles leave one by one. A batch costs one pass over each cached list, whatever its size.
function replaceInList(faces: DetectedFace[], updates: Map<string, (face: DetectedFace) => DetectedFace>): DetectedFace[] {
  let next: DetectedFace[] | null = null;
  for (let i = 0; i < faces.length; i++) {
    const update = updates.get(faces[i].faceId);
    if (!update) continue;
    next ??= faces.slice();
    next[i] = update(faces[i]);
  }
  return next ?? faces;
}

function updateFacesInCaches(qc: QueryClient, updates: Map<string, (face: DetectedFace) => DetectedFace>) {
  if (updates.size === 0) return;
  cancelLoadedFaceListFetches(qc);
  qc.setQueryData<DetectedFace[]>(ALL_FACES_KEY, (old) => (old ? replaceInList(old, updates) : old));
  qc.setQueriesData<DetectedFace[]>({ queryKey: PERSON_FACES_KEY }, (old) => (old ? replaceInList(old, updates) : old));
  // A face that changed hands no longer belongs to the list it was cached under; that person's
  // list is small, so just reload it rather than trying to splice it in at the right sort position.
  reconcilePersonLists(qc, new Set(updates.keys()));
}

// Apply a known field change to every cached list holding the faces. A patch must mirror what the
// backend does, so the splice and the eventual refetch agree.
function patchFacesInCaches(qc: QueryClient, patches: Map<string, Partial<DetectedFace>>) {
  const updates = new Map<string, (face: DetectedFace) => DetectedFace>();
  for (const [faceId, patch] of patches) updates.set(faceId, (face) => ({ ...face, ...patch }));
  updateFacesInCaches(qc, updates);
}

// The cached copy of each of `faceIds` found in any list — the whole collection first, then the
// per-person lists for the ones it doesn't hold (or when it isn't loaded).
function findCachedFaces(qc: QueryClient, faceIds: Set<string>): Map<string, DetectedFace> {
  const found = new Map<string, DetectedFace>();
  const collect = (faces: DetectedFace[] | undefined) => {
    if (!faces) return;
    for (const f of faces) if (faceIds.has(f.faceId) && !found.has(f.faceId)) found.set(f.faceId, f);
  };
  collect(qc.getQueryData<DetectedFace[]>(ALL_FACES_KEY));
  for (const query of qc.getQueryCache().findAll({ queryKey: PERSON_FACES_KEY })) {
    if (found.size === faceIds.size) break;
    collect(query.state.data as DetectedFace[] | undefined);
  }
  return found;
}

function reconcilePersonLists(qc: QueryClient, faceIds: Set<string>) {
  // Read back the already-patched faces: whichever list holds one now carries its new owner. A face
  // in no cache at all has nothing to reconcile — skipping it matters, since the whole-collection
  // list is only loaded by the cross-person inferred view.
  const faces = findCachedFaces(qc, faceIds);
  if (faces.size === 0) return;
  for (const query of qc.getQueryCache().findAll({ queryKey: PERSON_FACES_KEY })) {
    const listOwner = personFacesKeyOwner(query.queryKey);
    if (listOwner === null) continue;
    const cached = query.state.data as DetectedFace[] | undefined;
    if (!cached) continue;
    const present = new Set<string>();
    for (const f of cached) if (faces.has(f.faceId)) present.add(f.faceId);
    let missing = false;
    const leaving = new Set<string>();
    for (const [faceId, face] of faces) {
      const owned = ownerOf(face) === listOwner;
      if (owned && !present.has(faceId)) missing = true;
      else if (!owned && present.has(faceId)) leaving.add(faceId);
    }
    if (missing) void qc.invalidateQueries({ queryKey: query.queryKey, exact: true });
    else if (leaving.size > 0) qc.setQueryData<DetectedFace[]>(query.queryKey, cached.filter((f) => !leaving.has(f.faceId)));
  }
}

function removeFacesFromCaches(qc: QueryClient, faceIds: Set<string>) {
  if (faceIds.size === 0) return;
  cancelLoadedFaceListFetches(qc);
  const drop = (faces: DetectedFace[]) => (faces.some((f) => faceIds.has(f.faceId)) ? faces.filter((f) => !faceIds.has(f.faceId)) : faces);
  qc.setQueryData<DetectedFace[]>(ALL_FACES_KEY, (old) => (old ? drop(old) : old));
  qc.setQueriesData<DetectedFace[]>({ queryKey: PERSON_FACES_KEY }, (old) => (old ? drop(old) : old));
  for (const faceId of faceIds) qc.removeQueries({ queryKey: ["face", faceId] });
}

function addFacesToCaches(qc: QueryClient, faces: DetectedFace[]) {
  if (faces.length === 0) return;
  qc.setQueryData<DetectedFace[]>(ALL_FACES_KEY, (old) => {
    if (!old) return old;
    const known = new Set(old.map((f) => f.faceId));
    const fresh = faces.filter((f) => !known.has(f.faceId));
    return fresh.length > 0 ? [...old, ...fresh] : old;
  });
  const owners = new Set(faces.map(ownerOf).filter((o): o is string => !!o));
  for (const owner of owners) void qc.invalidateQueries({ queryKey: [...PERSON_FACES_KEY, owner], exact: true });
}

// Runs `task` over `items` with at most `limit` in flight, and returns the items whose task threw.
// Enough parallelism to hide the per-request round trip, bounded so a selection of hundreds
// doesn't open hundreds of sockets against the API at once.
const FACE_REQUEST_CONCURRENCY = 8;

async function runPooled<T>(items: T[], limit: number, task: (item: T) => Promise<void>): Promise<T[]> {
  const failed: T[] = [];
  let next = 0;
  async function worker() {
    while (next < items.length) {
      const item = items[next++];
      try {
        await task(item);
      } catch {
        failed.push(item);
      }
    }
  }
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, worker));
  return failed;
}

// Faces this tab just changed itself. Every mutation publishes an SSE "updated" frame for its face,
// which reaches this tab too; re-reading the face then would cost one GET and one more cache pass
// per face for a result the local patch already reflects. The first echo is swallowed, and the
// mark lapses on its own should that echo never come (a failed request, a dropped bus connection).
const SELF_MUTATION_ECHO_TTL_MS = 30_000;
const selfMutated = new Map<string, number>();

function markSelfMutated(faceIds: Iterable<string>) {
  const expiry = Date.now() + SELF_MUTATION_ECHO_TTL_MS;
  for (const faceId of faceIds) selfMutated.set(faceId, expiry);
}

function unmarkSelfMutated(faceIds: Iterable<string>) {
  for (const faceId of faceIds) selfMutated.delete(faceId);
}

function consumeSelfMutationEcho(faceId: string): boolean {
  const expiry = selfMutated.get(faceId);
  if (expiry === undefined) return false;
  selfMutated.delete(faceId);
  return expiry >= Date.now();
}

// Reconcile faces against the server: re-reading a single face costs one small JSON response,
// where invalidating ["faces"] would re-download the whole collection.
async function syncFacesFromServer(qc: QueryClient, api: ApiClient, faceIds: string[], deletedIds: string[] = []) {
  const fetched: DetectedFace[] = [];
  const gone = new Set(deletedIds);
  // Gone already, or unreachable — a stale entry is worse than none.
  const unreadable = await runPooled(faceIds, FACE_REQUEST_CONCURRENCY, async (faceId) => {
    fetched.push(await api.getFace(faceId));
  });
  for (const faceId of unreadable) gone.add(faceId);
  removeFacesFromCaches(qc, gone);
  if (fetched.length === 0) return;
  for (const face of fetched) qc.setQueryData<DetectedFace>(["face", face.faceId], face);
  // The crop file may have been rewritten under the same URL (a rotation re-crops every face of the
  // media), so make the next <img> request a different one.
  bumpFaceContentRevisions(qc, fetched.map((f) => f.faceId));
  const known = findCachedFaces(qc, new Set(fetched.map((f) => f.faceId)));
  // Replaced outright rather than merged: the server omits the fields it cleared, so merging would
  // keep a confirmed face's stale inferred bookkeeping alive.
  const updates = new Map<string, (face: DetectedFace) => DetectedFace>();
  for (const face of fetched) if (known.has(face.faceId)) updates.set(face.faceId, () => face);
  updateFacesInCaches(qc, updates);
  addFacesToCaches(qc, fetched.filter((f) => !known.has(f.faceId)));
}

// For the SSE bus: frames arrive one face at a time, and a bulk action anywhere (another tab, the
// CLI) sends hundreds in a burst, so they are gathered for a moment and reconciled as one batch.
const FACE_EVENT_BATCH_DELAY_MS = 250;

export function createFaceEventSync(qc: QueryClient, api: ApiClient): (faceId: string, action: "created" | "updated" | "deleted") => void {
  const pending = new Map<string, "created" | "updated" | "deleted">();
  let timer: ReturnType<typeof setTimeout> | null = null;

  function flush() {
    timer = null;
    const toFetch: string[] = [];
    const deleted: string[] = [];
    for (const [faceId, action] of pending) (action === "deleted" ? deleted : toFetch).push(faceId);
    pending.clear();
    void syncFacesFromServer(qc, api, toFetch, deleted);
  }

  return (faceId, action) => {
    if (action === "updated" && consumeSelfMutationEcho(faceId)) return;
    // The latest frame wins: a face deleted then re-created is fetched, one updated then deleted is dropped.
    pending.set(faceId, action);
    timer ??= setTimeout(flush, FACE_EVENT_BATCH_DELAY_MS);
  };
}

// Mirrors MediaServiceLive.faceUpdate: a face identified by a human keeps no inference bookkeeping,
// so the inferred fields go away with the confirmation.
export function identifiedPatch(personId: string): Partial<DetectedFace> {
  return {
    identifiedPersonId: personId,
    inferredIdentifiedPersonId: undefined,
    inferredIdentifiedPersonConfidence: undefined,
    inferredTimestamp: undefined,
    inferredIgnore: undefined,
  };
}

// One face at a time: patched once its call succeeds. The echo mark goes up before the request,
// since the server may publish the frame before the response gets back here.
function useFaceMutation<T>(faceIdOf: (vars: T) => string, call: (api: ApiClient, vars: T) => Promise<void>, patch: (vars: T) => Partial<DetectedFace>) {
  const { api } = useAuth();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (vars: T) => {
      markSelfMutated([faceIdOf(vars)]);
      try {
        await call(api, vars);
      } catch (err) {
        unmarkSelfMutated([faceIdOf(vars)]);
        throw err;
      }
    },
    onSuccess: (_data, vars) => patchFacesInCaches(qc, new Map([[faceIdOf(vars), patch(vars)]])),
  });
}

export function useSetFacePerson() {
  return useFaceMutation<{ faceId: string; personId: string }>(
    (v) => v.faceId,
    (api, v) => api.setFacePerson(v.faceId, v.personId),
    (v) => identifiedPatch(v.personId)
  );
}

export function useRemoveFacePerson() {
  return useFaceMutation<string>(
    (faceId) => faceId,
    (api, faceId) => api.removeFacePerson(faceId),
    () => ({ identifiedPersonId: undefined })
  );
}

export function useIgnoreFace() {
  return useFaceMutation<string>(
    (faceId) => faceId,
    (api, faceId) => api.ignoreFace(faceId),
    () => ({ inferredIgnore: true })
  );
}

export function useRestoreFace() {
  return useFaceMutation<string>(
    (faceId) => faceId,
    (api, faceId) => api.restoreFace(faceId),
    () => ({ inferredIgnore: undefined })
  );
}

export interface BulkFaceResult {
  succeeded: number;
  failed: number;
}

// A multi-selection, applied optimistically: the whole batch is patched into the caches in one go
// up front, so every selected tile leaves the view at once, then the requests run in parallel. The
// faces whose request failed are re-read from the server, which puts them back as they really are.
function useBulkFaceMutation<T extends { faceId: string }>(call: (api: ApiClient, item: T) => Promise<void>, patch: (item: T) => Partial<DetectedFace>) {
  const { api } = useAuth();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (items: T[]): Promise<BulkFaceResult> => {
      if (items.length === 0) return { succeeded: 0, failed: 0 };
      markSelfMutated(items.map((i) => i.faceId));
      patchFacesInCaches(qc, new Map(items.map((i) => [i.faceId, patch(i)])));
      const failed = await runPooled(items, FACE_REQUEST_CONCURRENCY, (item) => call(api, item));
      if (failed.length > 0) {
        const failedIds = failed.map((i) => i.faceId);
        unmarkSelfMutated(failedIds);
        await syncFacesFromServer(qc, api, failedIds);
      }
      return { succeeded: items.length - failed.length, failed: failed.length };
    },
  });
}

export function useConfirmFaces() {
  return useBulkFaceMutation<{ faceId: string; personId: string }>(
    (api, item) => api.setFacePerson(item.faceId, item.personId),
    (item) => identifiedPatch(item.personId)
  );
}

export function useIgnoreFaces() {
  return useBulkFaceMutation<{ faceId: string }>(
    (api, item) => api.ignoreFace(item.faceId),
    () => ({ inferredIgnore: true })
  );
}

export function useDeleteFace() {
  const { api } = useAuth();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (faceId: string) => api.deleteFace(faceId),
    onSuccess: (_data, faceId) => removeFacesFromCaches(qc, new Set([faceId])),
  });
}

export function useCreateFace() {
  const { api } = useAuth();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: { originalId: string; box: { x: number; y: number; width: number; height: number } }) => api.createFace(body),
    onSuccess: (face) => addFacesToCaches(qc, [face]),
  });
}
