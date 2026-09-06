"use client";

// A Set<string> useState that survives leaving and coming back to a tab (sessionStorage-backed,
// same pattern as the ad-hoc filter/sort/size persistence already used across app/persons/page.tsx).
// Used for the "to validate" face selection so switching tabs mid-review - e.g. clicking a face
// open in the Viewer, then coming back - doesn't lose which faces were selected.
import { useEffect, useState, type Dispatch, type SetStateAction } from "react";

const PREFIX = "sotohp:selection:";

function load(key: string): Set<string> {
  if (typeof window === "undefined") return new Set();
  try {
    const raw = sessionStorage.getItem(PREFIX + key);
    return raw ? new Set(JSON.parse(raw)) : new Set();
  } catch {
    return new Set();
  }
}

export function usePersistedSet(key: string): [Set<string>, Dispatch<SetStateAction<Set<string>>>] {
  const [value, setValue] = useState<Set<string>>(new Set());

  // Client-only, same reasoning as NavHeader's tab memory: reading sessionStorage during the
  // initial render would disagree with the statically-exported HTML on hydration.
  useEffect(() => {
    setValue(load(key));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);

  useEffect(() => {
    try {
      sessionStorage.setItem(PREFIX + key, JSON.stringify([...value]));
    } catch {
      /* ignore */
    }
  }, [key, value]);

  return [value, setValue];
}
