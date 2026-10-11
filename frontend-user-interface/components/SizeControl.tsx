"use client";

// Small / Medium / Maximum tile-size toggle, shared by the Persons face grids and the Clusters
// members views. The choice is remembered per `storageKey` for the session.
import { useEffect, useState } from "react";

export type SizeKey = "small" | "medium" | "max";

export function useSizeClass(storageKey: string): [SizeKey, (s: SizeKey) => void] {
  const [size, setSize] = useState<SizeKey>("max");
  useEffect(() => {
    try {
      const saved = sessionStorage.getItem(storageKey) as SizeKey | null;
      if (saved) setSize(saved);
    } catch {
      /* ignore */
    }
  }, [storageKey]);
  function persist(s: SizeKey) {
    setSize(s);
    try {
      sessionStorage.setItem(storageKey, s);
    } catch {
      /* ignore */
    }
  }
  return [size, persist];
}

export function SizeControl({ size, onChange, label = "Tile size" }: { size: SizeKey; onChange: (s: SizeKey) => void; label?: string }) {
  return (
    <div className="pf-size" role="group" aria-label={label}>
      {(["small", "medium", "max"] as SizeKey[]).map((s) => (
        <button key={s} type="button" className={size === s ? "active" : ""} onClick={() => onChange(s)}>
          {s === "max" ? "Maximum" : s[0].toUpperCase() + s.slice(1)}
        </button>
      ))}
    </div>
  );
}
