# OmniVid v2.1 Public Web and PWA Blueprint

## Goal

Turn the existing authenticated OmniVid workbench into an installable public Web application without changing its core video-processing workflows.

## Architecture

```text
Browser / Installed PWA
        |
        | HTTPS https://video.example.com
        v
Caddy public gateway
        |
        v
Nginx web container
  - serves React/Vite assets
  - proxies same-origin /api to Spring Boot
        |
        v
Spring Boot + Redis Session + MySQL
```

## Decisions

- Production uses one public domain and HTTPS.
- Frontend API calls remain same-origin through `/api`; no production CORS dependency is introduced.
- Caddy obtains and renews public TLS certificates automatically after a real domain resolves to the server.
- The PWA caches only the application shell and static assets.
- API responses, uploaded videos, media ranges, subtitles and private user data are never cached by the service worker.
- Offline mode only opens the application shell and explains that network access is required for business data.

## Responsive Boundary

- Desktop keeps the existing three-column workbench.
- Tablet reduces the right workspace into a full-width row.
- Mobile stacks upload, video/transcript and summary/Agent modules vertically.
- Popovers become full-width tool panels on mobile.

## Black-Box Acceptance

1. Build the frontend -> `dist/manifest.webmanifest` and `dist/sw.js` exist.
2. Open the site over HTTPS -> browser recognizes OmniVid as installable.
3. Install the PWA -> OmniVid opens in a standalone window with its own icon.
4. Disable network after one successful visit -> the application shell opens, but API/video requests are not served from stale cache.
5. Start the public Docker profile with a resolved domain -> `https://<domain>` loads the workbench and `/api/auth/me` remains same-origin.
