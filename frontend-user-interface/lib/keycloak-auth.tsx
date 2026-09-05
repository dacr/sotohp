"use client";

// Ports the exact DOMContentLoaded bootstrap sequence of the previous vanilla app: fetch auth
// config, skip Keycloak entirely when auth is disabled (and unregister any stale service worker),
// otherwise register the SW *before* Keycloak so it can start asking for a token right away, run
// Keycloak's check-sso flow, and only render the app once the very first image fetch would
// already carry a token (service worker primed) — this ordering avoids a burst of 401s on the
// first paint. See docs/internals — this file intentionally preserves timing/race-condition
// handling the original code discovered the hard way (SW first-install reload, controllerchange
// re-push, insecure-origin fallback) rather than "simplifying" it.
import Keycloak from "keycloak-js";
import { createContext, useContext, useEffect, useRef, useState, type ReactNode } from "react";
import { ApiClient } from "./api-client";

interface AuthContextValue {
  api: ApiClient;
  ready: boolean;
  authEnabled: boolean;
  username: string;
  logout: () => void;
  getToken: () => string | null | undefined;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth() must be used within <AuthProvider>");
  return ctx;
}

function sendTokenToSW(token: string | undefined | null) {
  if (typeof navigator === "undefined" || !navigator.serviceWorker) return;
  if (navigator.serviceWorker.controller && token) {
    navigator.serviceWorker.controller.postMessage({ type: "SET_TOKEN", token });
  }
}

let controllerChangeWired = false;
function wireControllerChangeRepush(getToken: () => string | undefined) {
  if (controllerChangeWired || typeof navigator === "undefined" || !navigator.serviceWorker) return;
  controllerChangeWired = true;
  navigator.serviceWorker.addEventListener("controllerchange", () => sendTokenToSW(getToken()));
}

async function pushTokenToServiceWorker(getToken: () => string | undefined) {
  if (typeof navigator === "undefined" || !navigator.serviceWorker) return;
  wireControllerChangeRepush(getToken);
  try {
    const reg = await Promise.race([
      navigator.serviceWorker.ready,
      new Promise<null>((resolve) => setTimeout(() => resolve(null), 2000)),
    ]);
    const token = getToken();
    if (reg && reg.active && token) {
      reg.active.postMessage({ type: "SET_TOKEN", token });
    }
    sendTokenToSW(token);
    if (!navigator.serviceWorker.controller && window.isSecureContext === false) {
      console.warn(
        "[sotohp] Service Worker not registered: the origin is insecure " +
          "(plain HTTP non-localhost). Image URLs fall back to ?token= query auth. " +
          "Use HTTPS or localhost/127.0.0.1 to drop the token from URLs."
      );
    }
  } catch {
    /* best-effort */
  }
}

function registerServiceWorker(getToken: () => string | undefined) {
  if (typeof navigator === "undefined" || !("serviceWorker" in navigator)) return;
  navigator.serviceWorker
    .register("/service-worker.js", { scope: "/" })
    .then((reg) => {
      // First-install race: the SW activates and calls clients.claim(), but
      // navigator.serviceWorker.controller isn't always updated on the registering page
      // before our image fetches go out. Reload once so the SW is definitely the
      // controller. A sessionStorage flag prevents an infinite reload loop.
      if (reg.active && !navigator.serviceWorker.controller && !sessionStorage.getItem("sw-first-claim")) {
        sessionStorage.setItem("sw-first-claim", "1");
        window.location.reload();
      } else {
        sessionStorage.removeItem("sw-first-claim");
      }
    })
    .catch((err) => console.error("Service Worker registration failed", err));

  navigator.serviceWorker.addEventListener("message", (event) => {
    if (event.data?.type === "REQUEST_TOKEN") sendTokenToSW(getToken());
  });
}

async function unregisterServiceWorker() {
  if (typeof navigator === "undefined" || !("serviceWorker" in navigator)) return;
  try {
    const regs = await navigator.serviceWorker.getRegistrations();
    await Promise.all(regs.map((r) => r.unregister().catch(() => {})));
  } catch {
    /* best-effort */
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [value, setValue] = useState<AuthContextValue | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const keycloakRef = useRef<Keycloak | null>(null);
  const initialized = useRef(false);

  useEffect(() => {
    if (initialized.current) return; // React 18/19 StrictMode double-invokes effects in dev
    initialized.current = true;

    const redirectUri = window.location.origin + window.location.pathname;

    (async () => {
      let authConfig: { enabled: boolean; url: string; realm: string; clientId: string } | null = null;
      try {
        const res = await fetch("/api/system/config");
        if (res.ok) authConfig = (await res.json()).auth;
      } catch {
        /* treated as auth disabled below */
      }

      if (!authConfig || !authConfig.enabled) {
        await unregisterServiceWorker();
        const api = new ApiClient("", { getToken: () => null });
        setValue({ api, ready: true, authEnabled: false, username: "", logout: () => {}, getToken: () => null });
        return;
      }

      const getToken = () => keycloakRef.current?.token;
      registerServiceWorker(getToken);

      const keycloak = new Keycloak({ url: authConfig.url, realm: authConfig.realm, clientId: authConfig.clientId });
      keycloakRef.current = keycloak;

      try {
        const authenticated = await keycloak.init({
          onLoad: "check-sso",
          silentCheckSsoRedirectUri: window.location.origin + "/silent-check-sso.html",
          checkLoginIframe: false,
          responseMode: "query",
          redirectUri,
        });

        if (authenticated) {
          const url = new URL(window.location.href);
          if (url.searchParams.has("code")) {
            ["code", "state", "session_state", "iss"].forEach((p) => url.searchParams.delete(p));
            window.history.replaceState({}, document.title, url.toString());
          }

          const api = new ApiClient("", {
            getToken,
            refreshToken: async () => {
              await keycloak.updateToken(30);
            },
            onToken: sendTokenToSW,
          });
          // Wait for the SW to be controlling the page, then push the token, so the very
          // first image fetch (mosaic tiles, map popups, etc.) already carries the
          // Authorization header instead of failing with a 401.
          await pushTokenToServiceWorker(getToken);
          setValue({
            api,
            ready: true,
            authEnabled: true,
            username: keycloak.tokenParsed?.preferred_username || "",
            logout: () => keycloak.logout({ redirectUri }),
            getToken,
          });
        } else {
          const url = new URL(window.location.href);
          if (url.searchParams.has("code")) {
            setErrorMessage("We received a login response but could not validate your session.");
          } else {
            keycloak.login({ redirectUri });
          }
        }
      } catch (err) {
        console.error("Keycloak init error", err);
        setErrorMessage(err instanceof Error ? err.message : "Failed to connect to authentication server.");
      }
    })();
  }, []);

  if (errorMessage) {
    return (
      <div style={{ padding: 40, textAlign: "center", fontFamily: "sans-serif" }}>
        <h2 style={{ color: "red" }}>Authentication Failed</h2>
        <p>{errorMessage}</p>
        <button style={{ padding: "10px 20px", cursor: "pointer" }} onClick={() => window.location.reload()}>
          Retry
        </button>
      </div>
    );
  }

  if (!value) return null; // waiting for auth bootstrap — avoid a flash of unauthenticated content

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
