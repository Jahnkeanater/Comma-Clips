// Minimal service worker so phones offer "Install app" / "Add to Home screen".
// Everything is fetched live from the server; nothing is cached offline.
self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", (e) => e.waitUntil(self.clients.claim()));
self.addEventListener("fetch", () => {});
