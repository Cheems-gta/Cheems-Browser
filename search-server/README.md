# Cheems Search · Open Source Backend

**Engine:** Mozilla GeckoView renders sites on Android. **Search:** SearXNG aggregates live results from external search providers; it is not an independent web index. **UI:** Cheems Browser draws its own result cards when this server exposes JSON. Both GeckoView (MPL 2.0) and SearXNG (AGPL-3.0) remain attributed to their original projects.

## Quick start on a Linux server with Docker Compose

1. Install Docker and Docker Compose, create this folder on a server and point your HTTPS domain to it with a reverse proxy.
2. Generate a long random secret locally: `openssl rand -hex 32`. Set it in an environment variable named `SEARXNG_SECRET`. Do not commit it.
3. Set `SEARXNG_BASE_URL=https://search.your-domain.example/` to your **real** domain.
4. Run `docker compose up -d`.
5. Put an HTTPS reverse proxy in front of `127.0.0.1:8080`. The port is not exposed to the public network by this Compose file.
6. Visit `https://search.your-domain.example/search?q=minecraft&format=json` to test. If you get a JSON results object, open Cheems Browser > Cheems Search settings and put in the HTTPS base URL.

Use your own domain in place of the example above. **No server has been deployed by this repository.** Running the Compose file locally on a computer does **not** make the search backend accessible from a phone over the Internet.

## Android app default

Without your own server, the Android browser uses a third-party **public** SearXNG instance at `https://searx.tiekoetter.com`. Public instances can change, block automated JSON/API requests or go offline. The app attempts the JSON API and, if unavailable, opens the same live search in GeckoView using the instance's HTML UI. Both paths query real web search; no fake hard-coded sample results. Public instance operators receive search queries.

## Licensing

- GeckoView: Mozilla Public License 2.0, https://www.mozilla.org/en-US/MPL/2.0/
- SearXNG: GNU AGPLv3, https://github.com/searxng/searxng
- When redistributing **modified** SearXNG itself, provide the corresponding source and required notices under AGPL-3.0. This folder contains configuration for upstream SearXNG, not a wholesale rewritten index.

SearXNG deployment documentation: https://docs.searxng.org/admin/installation-docker.html
SearXNG API documentation: https://docs.searxng.org/dev/search_api.html
