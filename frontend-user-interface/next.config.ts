import type { NextConfig } from "next";

// Static export: the Scala/Tapir API serves this UI as plain files from one process (see
// user-interfaces/api ApiApp.scala `htmlStaticAssets`) — no Node runtime in the deployment.
// Everything here is client-rendered anyway (Keycloak auth + a separate REST API), so a server
// component runtime buys nothing.
const nextConfig: NextConfig = {
  output: "export",
  images: {
    unoptimized: true, // no Image Optimization server available in a static export
  },
  // Static export otherwise emits flat "route.html" files (plus a same-named "route/" directory
  // of RSC prefetch metadata) — this makes it emit "route/index.html" instead, which is what the
  // backend's generic static-file server (Files.get's directory -> index.html resolution, see
  // ApiApp.scala htmlStaticAssets) expects for a clean `/route` URL with no extension.
  trailingSlash: true,
};

export default nextConfig;
