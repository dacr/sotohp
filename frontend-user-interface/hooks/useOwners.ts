"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "../lib/keycloak-auth";
import type { OwnerCreate, OwnerUpdate } from "../lib/api-client";

export function useOwners() {
  const { api } = useAuth();
  return useQuery({ queryKey: ["owners"], queryFn: () => api.listOwners() });
}

export function useCreateOwner() {
  const { api } = useAuth();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: OwnerCreate) => api.createOwner(body),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["owners"] }),
  });
}

export function useUpdateOwner() {
  const { api } = useAuth();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: OwnerUpdate }) => api.updateOwner(id, body),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["owners"] }),
  });
}
