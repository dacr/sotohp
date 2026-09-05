"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useMemo } from "react";
import { useAuth } from "../lib/keycloak-auth";
import type { PersonCreate, PersonUpdate } from "../lib/api-client";

export function usePersons() {
  const { api } = useAuth();
  return useQuery({ queryKey: ["persons"], queryFn: () => api.listPersons() });
}

// personId -> Person, built off the same shared ["persons"] cache entry every list/grid needs
// (replaces the old app.js-global `personsCache` Map).
export function usePersonsMap() {
  const { data: persons = [] } = usePersons();
  return useMemo(() => new Map(persons.map((p) => [p.id, p])), [persons]);
}

export function useCreatePerson() {
  const { api } = useAuth();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: PersonCreate) => api.createPerson(body),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["persons"] }),
  });
}

export function useUpdatePerson() {
  const { api } = useAuth();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: PersonUpdate }) => api.updatePerson(id, body),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["persons"] }),
  });
}

export function useDeletePerson() {
  const { api } = useAuth();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => api.deletePerson(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["persons"] }),
  });
}
