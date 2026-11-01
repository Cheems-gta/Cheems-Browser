#!/usr/bin/env python3
"""Cheems Search UI served over HTTP. Results come from our configured SearXNG.
This service has no user accounts, no tracking, and no third-party client scripts.
Production HTTPS/rate limiting must be handled by a trusted reverse proxy.
"""
import html
import json
import os
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, urlencode, urlsplit
from urllib.request import Request, urlopen
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SEARCH_API = os.getenv("SEARXNG_API", "http://searxng:8080/search")
PAGE = Path(__file__).with_name("page.html").read_text(encoding="utf-8")
CATEGORIES = {"general": "全部", "images": "图片", "videos": "视频", "news": "新闻"}


def escape(value):
    return html.escape(str(value or ""), quote=True)


def get_results(query, page, category):
    params = {
        "q": query,
        "format": "json",
        "safesearch": "1",
        "language": "auto",
        "pageno": str(page),
        "categories": category,
    }
    url = SEARCH_API + "?" + urlencode(params)
    request = Request(url, headers={
        "Accept": "application/json",
        "User-Agent": "CheemsSearch/0.2 (SearXNG proxy)",
    })
    with urlopen(request, timeout=22) as response:
        if response.status != 200:
            raise ValueError(f"search backend returned {response.status}")
        body = response.read(2_000_001)
        if len(body) > 2_000_000:
            raise ValueError("search response too large")
    return json.loads(body.decode("utf-8"))


def render(query="", page=1, category="general"):
    blocks, status, next_link = "", "", ""
    if query:
        try:
            data = get_results(query, page, category)
            results = data.get("results", [])
            for item in results[:30]:
                target = str(item.get("url") or "")
                parsed = urlsplit(target)
                if parsed.scheme not in ("http", "https") or not parsed.hostname:
                    continue
                title = escape(item.get("title") or parsed.hostname)
                desc = escape(item.get("content", ""))
                host = escape(parsed.hostname)
                link = escape(target)
                source = escape(item.get("engine", ""))
                blocks += f"""<article class="result">
<a class="result-title" href="{link}" rel="noopener noreferrer">{title}</a>
<div class="result-url">{host}</div>
<p>{desc}</p><span class="result-source">{source}</span>
</article>"""
            if blocks:
                status = "来自开源 SearXNG 的实时网页结果"
                next_params = urlencode({"q": query, "category": category, "pageno": page + 1})
                next_link = f'<a class="next" href="/search?{escape(next_params)}">下一页 →</a>'
            else:
                status = "暂时没有找到匹配的结果，可以换个关键词。"
        except (ValueError, OSError, HTTPError, URLError, json.JSONDecodeError, TimeoutError) as err:
            # Never expose backend URLs, query strings, or internal details to visitors.
            print(f"search backend error: {type(err).__name__}", flush=True)
            status = "搜索服务暂时不可用。请检查 SearXNG 是否已启动，以及服务器网络连接。"
    else:
        status = "不追踪你的搜索 · 自主界面 · 开源搜索后端"
    tabs = ""
    for key, title in CATEGORIES.items():
        params = urlencode({"q": query, "category": key, "pageno": 1})
        state = " active" if category == key else ""
        tabs += f'<a class="tab{state}" href="/search?{escape(params)}">{escape(title)}</a>'
    mapping = {
        "__QUERY__": escape(query),
        "__RESULTS__": blocks,
        "__STATUS__": escape(status),
        "__PAGINATION__": next_link,
        "__TABS__": tabs,
        "__PAGE__": str(page),
    }
    result = PAGE
    for marker, value in mapping.items():
        result = result.replace(marker, value)
    return result.encode("utf-8")


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        parsed = urlsplit(self.path)
        if parsed.path == "/healthz":
            body = b"ok"
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
        elif parsed.path not in ("/", "/search"):
            body = b"Not Found"
            self.send_response(404)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
        else:
            raw = parse_qs(parsed.query)
            query = raw.get("q", [""])[0].strip()[:200]
            try:
                page = max(1, min(int(raw.get("pageno", ["1"])[0]), 20))
            except ValueError:
                page = 1
            category = raw.get("category", ["general"])[0]
            if category not in CATEGORIES:
                category = "general"
            body = render(query, page, category)
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("X-Frame-Options", "DENY")
        self.send_header("Content-Security-Policy",
                         "default-src 'none'; style-src 'unsafe-inline'; "
                         "form-action 'self'; img-src 'self' data:; "
                         "base-uri 'none'; frame-ancestors 'none'")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        # Avoid retaining query strings in logs.
        pass


if __name__ == "__main__":
    print("Cheems Search UI ready on :8080", flush=True)
    ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
