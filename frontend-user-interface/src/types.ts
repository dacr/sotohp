// TypeScript interfaces based on OpenAPI components
export interface Location {
  latitude: number;
  longitude: number;
  altitude?: number;
}

export interface ExposureTime { numerator: number; denominator: number }

export type MediaKind = 'Photo' | 'Video'

export type Orientation =
  | 'Horizontal'
  | 'MirrorHorizontal'
  | 'MirrorHorizontalAndRotate270ClockWise'
  | 'MirrorHorizontalAndRotate90ClockWise'
  | 'MirrorVertical'
  | 'Rotate180'
  | 'Rotate270ClockWise'
  | 'Rotate90ClockWise'

export interface Original {
  id: string;
  storeId: string;
  kind: MediaKind;
  cameraShootDateTime?: string;
  cameraName?: string;
  artistInfo?: string;
  dimension?: { width: number; height: number };
  orientation?: Orientation;
  location?: Location;
  aperture?: number;
  exposureTime?: ExposureTime;
  iso?: number;
  focalLength?: number;
}

export interface Event {
  id: string;
  name: string;
  description?: string;
  keywords?: string[];
  timestamp?: string;
  originalId?: string;
}

export interface EventUpdate {
  name: string;
  description?: string;
  location?: Location;
  timestamp?: string; // ISO date-time
  originalId?: string;
  keywords?: string[];
}

export interface Owner {
  id: string;
  firstName: string;
  lastName: string;
  birthDate?: string;
}

export interface Store {
  id: string;
  ownerId: string;
  baseDirectory: string;
  includeMask?: string;
  ignoreMask?: string;
}

export interface Media {
  accessKey: string;
  original: Original;
  events?: Event[];
  description?: string;
  starred: boolean;
  keywords?: string[];
  orientation?: Orientation;
  shootDateTime?: string;
  userDefinedLocation?: Location;
  deductedLocation?: Location;
  location?: Location;
}

export type MediaSelector = 'first' | 'last' | 'next' | 'previous' | 'random'
