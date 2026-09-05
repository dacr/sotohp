"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "../lib/keycloak-auth";
import type { StoreCreate, StoreUpdate } from "../lib/api-client";

export function useStores() {
  const { api } = useAuth();
  return useQuery({ queryKey: ["stores"], queryFn: () => api.listStores() });
}

export function useCreateStore() {
  const { api } = useAuth();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: StoreCreate) => api.createStore(body),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["stores"] }),
  });
}

export function useUpdateStore() {
  const { api } = useAuth();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: StoreUpdate }) => api.updateStore(id, body),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["stores"] }),
  });
}
