"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect } from "react";
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

export function NavHeader() {
  const pathname = usePathname();
  const router = useRouter();
  const { authEnabled, logout } = useAuth();

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
      router.push(next.href);
    }
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [pathname, router]);

  return (
    <header>
      <h1>
        <img src="/favicon.svg" alt="" width={20} height={20} />
        Sotohp
      </h1>
      <nav className="tabs">
        {TABS.map((tab) => (
          <Link key={tab.href} href={tab.href} className={normalizePath(pathname) === tab.href ? "active" : ""} title={`${tab.label} (next tab: Alt+Page Down · previous tab: Alt+Page Up)`}>
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
