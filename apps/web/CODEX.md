# OmniVid Web Frontend Blueprint

## Positioning

The frontend is a dark enterprise workbench for demonstrating a Java backend + AI Agent project. It should feel like an operational console: dense, readable, calm, and credible for interviews.

## Current Scope

1. Keep the existing three-column workflow: upload/library, video/transcript, summary/Agent.
2. Keep LLM config and diagnostics as compact top-right popovers.
3. Improve visual hierarchy through spacing, panel surfaces, hover/focus states, and scroll behavior.
4. Do not change backend API contracts, upload behavior, ASR, summary, Agent, or URL import logic in visual-only iterations.

## Black-Box Acceptance

1. Open `http://127.0.0.1:5174` -> the first viewport should look like a professional AI video workbench, not a loose demo page.
2. Select an existing video -> video controls remain visible, the current citation card sits below the player, and transcript rows stay inside a scroll window.
3. Open LLM or diagnostics -> the popover should feel like a compact tool drawer and not obscure the main workflow more than necessary.
4. Run `npm run build` -> TypeScript and Vite build must pass.

## Public Web and PWA

1. Production API calls use same-origin `/api`; local Vite development falls back to `http://localhost:8080`.
2. The service worker may cache only the application shell and versioned static assets.
3. Never cache `/api`, video media, authenticated responses or user-generated content.
4. The installed PWA must preserve the existing responsive workbench instead of introducing a separate mobile product.
