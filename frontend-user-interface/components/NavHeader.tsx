"use client";

import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useEffect, useState } from "react";
import { useAuth } from "../lib/keycloak-auth";

// next.config.ts sets trailingSlash: true, so usePathname() returns "/mosaic/" while TABS below
// uses "/mosaic" (no slash) — an exact-match comparison against a raw pathname only ever succeeds
// for "/" itself. Strip a trailing slash (except on root) before comparing anywhere pathname meets
// a tab href.
function normalizePath(path: string): string {
  return path.length > 1 && path.endsWith("/") ? path.slice(0, -1) : path;
}

const TABS = [
  { href: "/", label: "Viewer" },
  { href: "/mosaic", label: "Mosaic" },
  { href: "/events", label: "Bags" },
  { href: "/portfolios", label: "Portfolios" },
  { href: "/map", label: "Map" },
  { href: "/persons", label: "Persons" },
  { href: "/owners", label: "Owners" },
  { href: "/stores", label: "Stores" },
  { href: "/settings", label: "Settings" },
];

// Each tab keeps its own sub-state (which person, which portfolio, ...) in its URL's search
// params already (see e.g. app/persons/page.tsx's `?person=`) - router.push updates it on every
// in-tab navigation. The only thing missing is that the nav links below always point at the bare
// tab href, discarding that state the moment you leave. Remembering the last full URL visited
// under each tab - persisted so it survives leaving for another tab or reloading - and using that
// as the link's href instead closes the gap: clicking back into a tab returns you to the exact
// person/portfolio/etc. you last had open there.
const TAB_MEMORY_KEY = "sotohp:last-tab-url";

function loadTabMemory(): Record<string, string> {
  if (typeof window === "undefined") return {};
  try {
    const raw = window.sessionStorage.getItem(TAB_MEMORY_KEY);
    return raw ? JSON.parse(raw) : {};
  } catch {
    return {};
  }
}

export function NavHeader() {
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const router = useRouter();
  const { authEnabled, logout } = useAuth();
  const [tabMemory, setTabMemory] = useState<Record<string, string>>({});

  // Client-only: reading sessionStorage during the initial render would make hydration disagree
  // with the statically-exported HTML (which has no access to it), so load it after mount instead.
  useEffect(() => {
    setTabMemory(loadTabMemory());
  }, []);

  useEffect(() => {
    const tab = TABS.find((t) => t.href === normalizePath(pathname));
    if (!tab) return;
    const query = searchParams.toString();
    const full = query ? `${pathname}?${query}` : pathname;
    setTabMemory((prev) => {
      if (prev[tab.href] === full) return prev;
      const next = { ...prev, [tab.href]: full };
      try {
        window.sessionStorage.setItem(TAB_MEMORY_KEY, JSON.stringify(next));
      } catch {
        /* ignore */
      }
      return next;
    });
  }, [pathname, searchParams]);

  // Alt+PageUp / Alt+PageDown cycle tabs, mirroring the previous app's global shortcut. Ignored
  // while typing, same as every other keyboard shortcut in this app.
  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if (!e.altKey || (e.key !== "PageUp" && e.key !== "PageDown")) return;
      const target = e.target as HTMLElement | null;
      if (target && /^(INPUT|TEXTAREA|SELECT)$/.test(target.tagName)) return;
      e.preventDefault();
      const index = TABS.findIndex((t) => t.href === normalizePath(pathname));
      const current = index === -1 ? 0 : index;
      const delta = e.key === "PageDown" ? 1 : -1;
      const next = TABS[(current + delta + TABS.length) % TABS.length];
      router.push(tabMemory[next.href] ?? next.href);
    }
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [pathname, router, tabMemory]);

  return (
    <header>
      <h1>
        <img src="/favicon.svg" alt="" width={20} height={20} />
        Sotohp
      </h1>
      <nav className="tabs">
        {TABS.map((tab) => (
          <Link key={tab.href} href={tabMemory[tab.href] ?? tab.href} className={normalizePath(pathname) === tab.href ? "active" : ""} title={`${tab.label} (next tab: Alt+Page Down · previous tab: Alt+Page Up)`}>
            {tab.label}
          </Link>
        ))}
        {authEnabled && (
          <button className="logout-btn" onClick={logout}>
            Logout
          </button>
        )}
      </nav>
    </header>
  );
}
