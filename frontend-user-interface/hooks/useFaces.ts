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
// (lib/live-events.tsx) reconciles against the server one face at a time. The lists still refetch
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
function bumpFaceContentRevision(qc: QueryClient, faceId: string) {
  qc.setQueryData<Record<string, number>>(FACE_CONTENT_REVISION_KEY, (old) => ({ ...(old ?? {}), [faceId]: (old?.[faceId] ?? 0) + 1 }));
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

function replaceInList(faces: DetectedFace[], faceId: string, update: (face: DetectedFace) => DetectedFace): DetectedFace[] {
  const index = faces.findIndex((f) => f.faceId === faceId);
  if (index < 0) return faces;
  const next = faces.slice();
  next[index] = update(next[index]);
  return next;
}

function updateFaceInCaches(qc: QueryClient, faceId: string, update: (face: DetectedFace) => DetectedFace) {
  cancelLoadedFaceListFetches(qc);
  qc.setQueryData<DetectedFace[]>(ALL_FACES_KEY, (old) => (old ? replaceInList(old, faceId, update) : old));
  qc.setQueriesData<DetectedFace[]>({ queryKey: PERSON_FACES_KEY }, (old) => (old ? replaceInList(old, faceId, update) : old));
  // A face that changed hands no longer belongs to the list it was cached under; that person's
  // list is small, so just reload it rather than trying to splice it in at the right sort position.
  reconcilePersonLists(qc, faceId);
}

// Apply a known field change to every cached list holding the face. `patch` must mirror what the
// backend does, so the splice and the eventual refetch agree.
function patchFaceInCaches(qc: QueryClient, faceId: string, patch: Partial<DetectedFace>) {
  updateFaceInCaches(qc, faceId, (face) => ({ ...face, ...patch }));
}

function findCachedFace(qc: QueryClient, faceId: string): DetectedFace | undefined {
  const inAll = qc.getQueryData<DetectedFace[]>(ALL_FACES_KEY)?.find((f) => f.faceId === faceId);
  if (inAll) return inAll;
  for (const query of qc.getQueryCache().findAll({ queryKey: PERSON_FACES_KEY })) {
    const hit = (query.state.data as DetectedFace[] | undefined)?.find((f) => f.faceId === faceId);
    if (hit) return hit;
  }
  return undefined;
}

function reconcilePersonLists(qc: QueryClient, faceId: string) {
  // Read back the already-patched face: whichever list holds it now carries the new owner. With
  // the face in no cache at all there is nothing to reconcile — bailing out matters, since the
  // whole-collection list is only loaded by the cross-person inferred view.
  const face = findCachedFace(qc, faceId);
  if (!face) return;
  const owner = ownerOf(face);
  for (const query of qc.getQueryCache().findAll({ queryKey: PERSON_FACES_KEY })) {
    const listOwner = personFacesKeyOwner(query.queryKey);
    if (listOwner === null) continue;
    const cached = query.state.data as DetectedFace[] | undefined;
    if (!cached) continue;
    const present = cached.some((f) => f.faceId === faceId);
    if (listOwner === owner && !present) void qc.invalidateQueries({ queryKey: query.queryKey, exact: true });
    else if (listOwner !== owner && present) qc.setQueryData<DetectedFace[]>(query.queryKey, cached.filter((f) => f.faceId !== faceId));
  }
}

function removeFaceFromCaches(qc: QueryClient, faceId: string) {
  cancelLoadedFaceListFetches(qc);
  const drop = (faces: DetectedFace[]) => (faces.some((f) => f.faceId === faceId) ? faces.filter((f) => f.faceId !== faceId) : faces);
  qc.setQueryData<DetectedFace[]>(ALL_FACES_KEY, (old) => (old ? drop(old) : old));
  qc.setQueriesData<DetectedFace[]>({ queryKey: PERSON_FACES_KEY }, (old) => (old ? drop(old) : old));
  qc.removeQueries({ queryKey: ["face", faceId] });
}

function addFaceToCaches(qc: QueryClient, face: DetectedFace) {
  qc.setQueryData<DetectedFace[]>(ALL_FACES_KEY, (old) => (old && !old.some((f) => f.faceId === face.faceId) ? [...old, face] : old));
  const owner = ownerOf(face);
  if (owner) void qc.invalidateQueries({ queryKey: [...PERSON_FACES_KEY, owner], exact: true });
}

// Reconcile one face against the server, for the SSE bus: re-reading a single face costs one small
// JSON response, where invalidating ["faces"] would re-download the whole collection on every
// create/update/delete anyone makes.
export async function syncFaceFromServer(qc: QueryClient, api: ApiClient, faceId: string, action: "created" | "updated" | "deleted") {
  if (action === "deleted") {
    removeFaceFromCaches(qc, faceId);
    return;
  }
  let face: DetectedFace;
  try {
    face = await api.getFace(faceId);
  } catch {
    // Gone already, or unreachable — a stale entry is worse than none.
    removeFaceFromCaches(qc, faceId);
    return;
  }
  qc.setQueryData<DetectedFace>(["face", faceId], face);
  // The crop file may have been rewritten under the same URL (a rotation re-crops every face of the
  // media), so make the next <img> request a different one.
  bumpFaceContentRevision(qc, faceId);
  const known = qc.getQueryData<DetectedFace[]>(ALL_FACES_KEY)?.some((f) => f.faceId === faceId);
  // Replaced outright rather than merged: the server omits the fields it cleared, so merging would
  // keep a confirmed face's stale inferred bookkeeping alive.
  if (known) updateFaceInCaches(qc, faceId, () => face);
  else addFaceToCaches(qc, face);
}

export function useSetFacePerson() {
  const { api } = useAuth();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ faceId, personId }: { faceId: string; personId: string }) => api.setFacePerson(faceId, personId),
    onSuccess: (_data, { faceId, personId }) =>
      // Mirrors MediaServiceLive.faceUpdate: a face identified by a human keeps no inference
      // bookkeeping, so the inferred fields go away with the confirmation.
      patchFaceInCaches(qc, faceId, {
        identifiedPersonId: personId,
        inferredIdentifiedPersonId: undefined,
        inferredIdentifiedPersonConfidence: undefined,
        inferredTimestamp: undefined,
        inferredIgnore: undefined,
      }),
  });
}

export function useRemoveFacePerson() {
  const { api } = useAuth();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (faceId: string) => api.removeFacePerson(faceId),
    onSuccess: (_data, faceId) => patchFaceInCaches(qc, faceId, { identifiedPersonId: undefined }),
  });
}

export function useIgnoreFace() {
  const { api } = useAuth();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (faceId: string) => api.ignoreFace(faceId),
    onSuccess: (_data, faceId) => patchFaceInCaches(qc, faceId, { inferredIgnore: true }),
  });
}

export function useRestoreFace() {
  const { api } = useAuth();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (faceId: string) => api.restoreFace(faceId),
    onSuccess: (_data, faceId) => patchFaceInCaches(qc, faceId, { inferredIgnore: undefined }),
  });
}

export function useDeleteFace() {
  const { api } = useAuth();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (faceId: string) => api.deleteFace(faceId),
    onSuccess: (_data, faceId) => removeFaceFromCaches(qc, faceId),
  });
}

export function useCreateFace() {
  const { api } = useAuth();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: { originalId: string; box: { x: number; y: number; width: number; height: number } }) => api.createFace(body),
    onSuccess: (face) => addFaceToCaches(qc, face),
  });
}
