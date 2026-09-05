// Logarithmic scroll<->timestamp mapping for the mosaic timeline rail: recent years get
// proportionally more scroll space than older ones, since photo density skews recent.
const K = 3;

export function scrollPositionToTimestamp(scrollRatio: number, oldestTime: string, newestTime: string): string | null {
  const oldestMs = new Date(oldestTime).getTime();
  const newestMs = new Date(newestTime).getTime();
  const totalRange = newestMs - oldestMs;
  if (totalRange <= 0) return new Date(newestMs).toISOString();
  const normalizedPosition = (Math.exp(K * scrollRatio) - 1) / (Math.exp(K) - 1);
  return new Date(newestMs - totalRange * normalizedPosition).toISOString();
}

export function timestampToScrollPosition(timestamp: string, oldestTime: string, newestTime: string): number {
  const oldestMs = new Date(oldestTime).getTime();
  const newestMs = new Date(newestTime).getTime();
  const targetMs = new Date(timestamp).getTime();
  const totalRange = newestMs - oldestMs;
  if (totalRange <= 0) return 0;
  const normalizedPosition = (newestMs - targetMs) / totalRange;
  const scrollRatio = Math.log(normalizedPosition * (Math.exp(K) - 1) + 1) / K;
  return Math.max(0, Math.min(1, scrollRatio));
}
