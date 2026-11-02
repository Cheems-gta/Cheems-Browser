#!/usr/bin/env python3
"""Probe ordinary user-facing HTML search results from candidate providers."""
import urllib.request,urllib.error,urllib.parse
q=urllib.parse.quote("Firefox download")
targets={
 "priv.au":"https://priv.au/search?q="+q,
 "search.inetol.net":"https://search.inetol.net/search?q="+q,
 "searx.be":"https://searx.be/search?q="+q,
 "searx.tiekoetter.com":"https://searx.tiekoetter.com/search?q="+q,
 "duckduckgo-html":"https://html.duckduckgo.com/html/?q="+q,
 "google-web":"https://www.google.com/search?q="+q,
 "bing-web":"https://www.bing.com/search?q="+q,
}
for name,url in targets.items():
 try:
  req=urllib.request.Request(url,headers={"Accept":"text/html","User-Agent":"Mozilla/5.0"})
  with urllib.request.urlopen(req,timeout=7) as r:
   data=r.read(150000).decode("utf-8","replace").lower()
   print(name, r.status, "bytes",len(data),"links",data.count("<a "), "result",data.count("result"))
 except urllib.error.HTTPError as e:print(name,"HTTP",e.code)
 except Exception as e:print(name,type(e).__name__,str(e)[:80])
