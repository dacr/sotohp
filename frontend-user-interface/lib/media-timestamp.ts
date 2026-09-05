import type { Media } from "./api-client";

// Effective timestamp used by the backend to order media: shootDateTime (user override) →
// original.cameraShootDateTime → first bag timestamp. Display/timeline only — actual media
// ordering always trusts the backend's own next/previous traversal, never a frontend resort.
export function mediaTimestamp(media: Media | undefined | null): string | null {
  if (!media) return null;
  if (media.shootDateTime) return media.shootDateTime;
  if (media.original?.cameraShootDateTime) return media.original.cameraShootDateTime;
  if (media.bag?.timestamp) return media.bag.timestamp;
  return null;
}
