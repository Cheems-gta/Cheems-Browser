#!/usr/bin/env python3
"""Check a few SearXNG public JSON APIs; outputs only hostname/status/result count."""
import json, urllib.request, urllib.error, urllib.parse, socket
hosts=["priv.au","search.inetol.net","searx.be","searx.tiekoetter.com","search.ononoki.org"]
for host in hosts:
    url="https://"+host+"/search?"+urllib.parse.urlencode({"q":"firefox","format":"json","safesearch":"2"})
    req=urllib.request.Request(url,headers={"Accept":"application/json","User-Agent":"Mozilla/5.0 (compatible; CheemsSearchTest/0.2.5)"})
    try:
        with urllib.request.urlopen(req,timeout=7) as res:
            b=res.read(500_000)
            payload=json.loads(b)
            n=len(payload.get("results",[]))
            print(host, "status",res.status,"JSON results",n, "content_type",res.headers.get("Content-Type"))
    except urllib.error.HTTPError as e:
        print(host, "HTTP",e.code)
    except Exception as e:
        print(host, type(e).__name__,str(e)[:120])
