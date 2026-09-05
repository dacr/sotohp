"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "../lib/keycloak-auth";
import type { Asset, PortfolioCreate, PortfolioUpdate } from "../lib/api-client";

export function usePortfolios() {
  const { api } = useAuth();
  return useQuery({
    queryKey: ["portfolios"],
    queryFn: async () => {
      const portfolios = await api.listPortfolios();
      return [...portfolios].sort((a, b) => (a.name || "").localeCompare(b.name || ""));
    },
  });
}

export function usePortfolio(portfolioId: string | null) {
  const { api } = useAuth();
  return useQuery({
    queryKey: ["portfolio", portfolioId],
    queryFn: () => api.getPortfolio(portfolioId!),
    enabled: !!portfolioId,
  });
}

function useInvalidatePortfolios() {
  const qc = useQueryClient();
  return (portfolioId?: string) => {
    qc.invalidateQueries({ queryKey: ["portfolios"] });
    if (portfolioId) qc.invalidateQueries({ queryKey: ["portfolio", portfolioId] });
  };
}

export function useCreatePortfolio() {
  const { api } = useAuth();
  const invalidate = useInvalidatePortfolios();
  return useMutation({
    mutationFn: (body: PortfolioCreate) => api.createPortfolio(body),
    onSuccess: () => invalidate(),
  });
}

export function useUpdatePortfolio() {
  const { api } = useAuth();
  const invalidate = useInvalidatePortfolios();
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: PortfolioUpdate }) => api.updatePortfolio(id, body),
    onSuccess: (_data, { id }) => invalidate(id),
  });
}

export function useDeletePortfolio() {
  const { api } = useAuth();
  const invalidate = useInvalidatePortfolios();
  return useMutation({
    mutationFn: (id: string) => api.deletePortfolio(id),
    onSuccess: () => invalidate(),
  });
}

export function useAddPortfolioAsset() {
  const { api } = useAuth();
  const invalidate = useInvalidatePortfolios();
  return useMutation({
    mutationFn: ({ portfolioId, asset }: { portfolioId: string; asset: Asset }) => api.addPortfolioAsset(portfolioId, asset),
    onSuccess: (_data, { portfolioId }) => invalidate(portfolioId),
  });
}

export function useUpdatePortfolioAsset() {
  const { api } = useAuth();
  const invalidate = useInvalidatePortfolios();
  return useMutation({
    mutationFn: ({ portfolioId, oldAsset, newAsset }: { portfolioId: string; oldAsset: Asset; newAsset: Asset }) => api.updatePortfolioAsset(portfolioId, oldAsset, newAsset),
    onSuccess: (_data, { portfolioId }) => invalidate(portfolioId),
  });
}

export function useRemovePortfolioAsset() {
  const { api } = useAuth();
  const invalidate = useInvalidatePortfolios();
  return useMutation({
    mutationFn: ({ portfolioId, asset }: { portfolioId: string; asset: Asset }) => api.removePortfolioAsset(portfolioId, asset),
    onSuccess: (_data, { portfolioId }) => invalidate(portfolioId),
  });
}
