# 🦊 Cheems Search — Open source search backend

The browser uses **Mozilla GeckoView** for rendering and **SearXNG** for querying
multiple search engines. It is a metasearch engine, not an independently crawled
index of every website. The native Cheems Search result cards consume SearXNG's
JSON API; the fallback HTML page still renders using GeckoView.

## Default: works without owning a server

Cheems Browser points to the public SearXNG instance **https://priv.au**.
This is operated by a third party, can be rate-limited or become unavailable,
and may disable its JSON API. In that case the browser opens its normal HTML
results. You can switch providers in the search page's gear button.

## Your own instance (requires a Linux computer or VPS with Docker)

1. Install Docker Compose, and change to this `searxng/` directory.
2. Generate a random secret on your server (for example: `openssl rand -hex 32`)
   and put `SEARXNG_SECRET=<generated-value>` into a local `.env` file. Do
   NOT commit secrets to GitHub.
3. Run `docker compose up -d`.
4. On the server, SearXNG is available at `http://127.0.0.1:8888`.
5. Expose it through a properly configured **HTTPS reverse proxy** under your
   own domain, with appropriate access controls/rate limiting. Do not publicly
   expose the private Compose setup as-is.
6. In the Android app, tap the ⚙ button on the Cheems Search screen and set
   your HTTPS address. The JSON API is enabled in the provided `settings.yml`.

Test your setup by requesting `https://YOUR_DOMAIN/search?q=hello&format=json`
and checking that JSON results are returned.

## Security and attribution

- All in-app search requests specify `safesearch=2` (subject to engine support).
- Settings accept HTTPS instance URLs (not HTTP).
- No SearXNG source is copied into the Android app. The official upstream
  container runs as a separate backend, licensed under AGPL-3.0:
  https://github.com/searxng/searxng
- Browser rendering is performed by Mozilla GeckoView (MPL-2.0):
  https://mozilla.github.io/geckoview/
- Public instances are community services; don't assume they're available forever.
