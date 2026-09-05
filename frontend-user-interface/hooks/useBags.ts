"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "../lib/keycloak-auth";
import type { BagUpdate } from "../lib/api-client";

export function useBags() {
  const { api } = useAuth();
  return useQuery({
    queryKey: ["bags"],
    queryFn: async () => {
      const bags = await api.listBags();
      return [...bags].sort((a, b) => (b.timestamp ? Date.parse(b.timestamp) : 0) - (a.timestamp ? Date.parse(a.timestamp) : 0));
    },
  });
}

export function useUpdateBag() {
  const { api } = useAuth();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: BagUpdate }) => api.updateBag(id, body),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["bags"] }),
  });
}
