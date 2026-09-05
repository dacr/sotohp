"use client";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useState, type ReactNode } from "react";
import { AuthProvider } from "./keycloak-auth";
import { LiveEventsProvider } from "./live-events";

export function AppProviders({ children }: { children: ReactNode }) {
  const [queryClient] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            staleTime: 30_000,
            refetchOnWindowFocus: false, // the SSE bus keeps the cache fresh; no need to poll on focus
          },
        },
      })
  );

  return (
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <LiveEventsProvider>{children}</LiveEventsProvider>
      </AuthProvider>
    </QueryClientProvider>
  );
}
