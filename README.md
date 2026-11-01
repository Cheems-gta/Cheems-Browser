# 🦊 Cheems Browser V0.2 — GeckoView + Cheems Search

An Android browser with our own UI and **two open-source components**:

- **Mozilla GeckoView** renders websites: no Android WebView and no Bing wrapper.
- **SearXNG** aggregates web results. The native **Cheems Search** screen displays
  JSON results in our own cards; when an instance disables JSON, GeckoView opens
  the SearXNG HTML results page automatically.

This is a **metasearch engine**, not a brand-new crawl/index of the whole web.

## Download APK

Open [GitHub Actions](https://github.com/Cheems-gta/Cheems-Browser/actions),
select the latest successful **Build Cheems Browser APK** run, and download its
`CheemsBrowser-V0.2-APK` artifact. Unzip to get `app-debug.apk`.
Artifacts require GitHub sign-in. Android installation testing is still needed.

## How to use

Type a URL to browse normally. Type keywords to open Cheems Search. Choose
web, image, or video results. Tap a result to open it inside GeckoView.
Use ⚙ on the search screen to select your own SearXNG HTTPS instance.

By default a public SearXNG instance (https://priv.au) is used. This instance
does not belong to Cheems Browser, and access/JSON may be limited. Our custom
native cards work with an instance that exposes `format=json`; otherwise we
fall back to SearXNG's HTML result page. For self-hosting see
[`searxng/README.md`](searxng/README.md).

## Source structure

- `CheemsGeckoBrowser_V0.1_Source.zip`: original GeckoView project, retained
  for compatibility with the verified Gradle build.
- `android/CheemsSearchActivity.kt`: native search results screen.
- `.github/workflows/build.yml`: unpacks the original project, applies
  targeted changes, compiles V0.2, uploads the APK.
- `searxng/`: optional self-hosted open-source search service.

GeckoView: Mozilla Public License 2.0. SearXNG: GNU AGPLv3. Search results are
obtained from third-party search services through SearXNG, not our own global index.
