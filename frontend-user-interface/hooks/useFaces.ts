"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "../lib/keycloak-auth";

export function useAllFaces() {
  const { api } = useAuth();
  return useQuery({ queryKey: ["faces"], queryFn: () => api.listFaces() });
}

export function usePersonFaces(personId: string | undefined | null) {
  const { api } = useAuth();
  return useQuery({
    queryKey: ["personFaces", personId],
    queryFn: async () => {
      const faces = await api.listPersonFaces(personId!);
      return [...faces].sort((a, b) => new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime());
    },
    enabled: !!personId,
  });
}

function useInvalidateFaces() {
  const qc = useQueryClient();
  return () => {
    qc.invalidateQueries({ queryKey: ["faces"] });
    qc.invalidateQueries({ queryKey: ["personFaces"] });
  };
}

export function useSetFacePerson() {
  const { api } = useAuth();
  const invalidate = useInvalidateFaces();
  return useMutation({
    mutationFn: ({ faceId, personId }: { faceId: string; personId: string }) => api.setFacePerson(faceId, personId),
    onSuccess: invalidate,
  });
}

export function useRemoveFacePerson() {
  const { api } = useAuth();
  const invalidate = useInvalidateFaces();
  return useMutation({
    mutationFn: (faceId: string) => api.removeFacePerson(faceId),
    onSuccess: invalidate,
  });
}

export function useIgnoreFace() {
  const { api } = useAuth();
  const invalidate = useInvalidateFaces();
  return useMutation({
    mutationFn: (faceId: string) => api.ignoreFace(faceId),
    onSuccess: invalidate,
  });
}

export function useRestoreFace() {
  const { api } = useAuth();
  const invalidate = useInvalidateFaces();
  return useMutation({
    mutationFn: (faceId: string) => api.restoreFace(faceId),
    onSuccess: invalidate,
  });
}

export function useDeleteFace() {
  const { api } = useAuth();
  const invalidate = useInvalidateFaces();
  return useMutation({
    mutationFn: (faceId: string) => api.deleteFace(faceId),
    onSuccess: invalidate,
  });
}

export function useCreateFace() {
  const { api } = useAuth();
  const invalidate = useInvalidateFaces();
  return useMutation({
    mutationFn: (body: { originalId: string; box: { x: number; y: number; width: number; height: number } }) => api.createFace(body),
    onSuccess: invalidate,
  });
}
