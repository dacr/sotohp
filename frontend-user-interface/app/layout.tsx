import type { Metadata } from "next";
import "leaflet/dist/leaflet.css";
import "leaflet.markercluster/dist/MarkerCluster.css";
import "leaflet.markercluster/dist/MarkerCluster.Default.css";
import "./globals.css";
import { AppProviders } from "../lib/providers";
import { NavHeader } from "../components/NavHeader";

export const metadata: Metadata = {
  title: "Sotohp",
  icons: { icon: "/favicon.svg" },
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>
        <AppProviders>
          <NavHeader />
          <main>{children}</main>
        </AppProviders>
      </body>
    </html>
  );
}
