// Scope: `/` (served from /service-worker.js so default scope covers /api/*).
// Purpose: inject the Authorization header onto image content fetches so the
// bearer token never appears in URLs (no leak into access logs, history, etc.).
let authToken = null;

self.addEventListener('install', (event) => {
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  // Take control of all open clients (including the page that registered us)
  // and ask them to push their current token immediately — otherwise the first
  // image fetches after a fresh install have no token in the SW.
  event.waitUntil((async () => {
    await self.clients.claim();
    const clients = await self.clients.matchAll({ includeUncontrolled: true });
    for (const client of clients) {
      try { client.postMessage({ type: 'REQUEST_TOKEN' }); } catch {}
    }
  })());
});

self.addEventListener('message', (event) => {
  if (event.data && event.data.type === 'SET_TOKEN') {
    authToken = event.data.token;
  }
});

// Ask every client for a fresh token. Used at activate (one-time, normal
// startup) and on demand inside the fetch handler (if the SW has been evicted
// from memory and its in-process token is gone).
async function requestTokenFromClients() {
  const clients = await self.clients.matchAll({ includeUncontrolled: true });
  for (const client of clients) {
    try { client.postMessage({ type: 'REQUEST_TOKEN' }); } catch {}
  }
}

self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url);
  // Match every media/face content endpoint, with or without a trailing
  // path segment — face URLs end in `/content` (no slash), media URLs end in
  // `/content/normalized` etc.
  if (!url.pathname.match(/^\/api\/(media|face)\/[^/]+\/content(\/|$)/)) return;

  event.respondWith((async () => {
    // If we have no token (fresh SW after install OR after browser eviction),
    // ask the page to push one and wait briefly. Without this the request
    // would 401 simply because the SW lost its in-memory state.
    if (!authToken) {
      await requestTokenFromClients();
      const deadline = Date.now() + 1000;
      while (!authToken && Date.now() < deadline) {
        await new Promise(r => setTimeout(r, 25));
      }
    }
    if (authToken) {
      // Build a fresh request (not `new Request(event.request, ...)`) so the
      // mode is the default "cors" rather than the `<img>`-default "no-cors"
      // it would inherit. In `no-cors` mode the Authorization header is not
      // on the CORS-safelist and gets stripped by the browser before sending
      // — that's why the backend would see no auth and return 401.
      return fetch(event.request.url, {
        method: event.request.method,
        headers: { 'Authorization': `Bearer ${authToken}` },
        credentials: 'same-origin',
        cache: event.request.cache,
        redirect: event.request.redirect,
        referrer: event.request.referrer,
      });
    }
    // Still no token — let the request go through so the page sees a real 401
    // and can react (re-auth, login redirect, etc.) rather than hang.
    return fetch(event.request);
  })());
});
