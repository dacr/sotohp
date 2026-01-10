const CACHE_NAME = 'sotohp-images-v1';
let authToken = null;

self.addEventListener('install', (event) => {
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(self.clients.claim());
});

self.addEventListener('message', (event) => {
  if (event.data && event.data.type === 'SET_TOKEN') {
    authToken = event.data.token;
  }
});

self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url);
  // Intercept requests to our image content endpoints
  if (url.pathname.match(/\/api\/(media|face)\/.*\/content\//)) {
    if (authToken) {
      // If we have a token, we clone the request and add the Authorization header.
      // We rely on the browser's HTTP cache (Disk Cache) which respects the Cache-Control header
      // sent by the backend. We do NOT use the Cache API manually here because that would duplicate storage.
      // By using the same URL (without ?token=...), the browser will naturally cache the response.
      
      const modifiedRequest = new Request(event.request, {
        headers: new Headers({
          ...Object.fromEntries(event.request.headers.entries()),
          'Authorization': `Bearer ${authToken}`
        })
      });
      
      event.respondWith(fetch(modifiedRequest));
    }
    // If no token, let it fail (or succeed if public/cookie auth works) naturally.
  }
});
