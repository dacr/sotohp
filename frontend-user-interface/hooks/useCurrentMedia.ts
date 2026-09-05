"use client";

// The Viewer's "currently displayed media" lives in the React Query cache keyed by accessKey —
// not just local state — so an SSE "media" event from another connected client editing the same
// photo (star, rotate, description, ...) refetches it and the Viewer updates live, the same way
// every list tab does. `navigate` seeds the cache with the object the navigation endpoint already
// returned, so switching photos never pays a redundant fetch.
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useCallback, useState } from "react";
import { useAuth } from "../lib/keycloak-auth";
import type { Media, MediaSelector } from "../lib/api-client";

export function useCurrentMedia() {
  const { api } = useAuth();
  const qc = useQueryClient();
  const [accessKey, setAccessKey] = useState<string | null>(null);

  const query = useQuery({
    queryKey: ["media", accessKey],
    queryFn: () => api.getMediaByKey(accessKey!),
    enabled: !!accessKey,
  });

  const navigate = useCallback(
    async (select: MediaSelector, referenceMediaAccessKey?: string, referenceMediaTimestamp?: string) => {
      const media = await api.getMedia(select, referenceMediaAccessKey, referenceMediaTimestamp);
      qc.setQueryData(["media", media.accessKey], media);
      setAccessKey(media.accessKey);
      return media;
    },
    [api, qc]
  );

  const loadByKey = useCallback(
    async (key: string) => {
      const media = await api.getMediaByKey(key);
      qc.setQueryData(["media", key], media);
      setAccessKey(key);
      return media;
    },
    [api, qc]
  );

  const setMedia = useCallback(
    (media: Media) => {
      qc.setQueryData(["media", media.accessKey], media);
    },
    [qc]
  );

  return { media: query.data ?? null, accessKey, navigate, loadByKey, setMedia };
}
