"""What Spotify's web player asks of Spotify as it opens, phone and computer: each ask's host, whether its service
worker made it, whether it carried a Bearer pass and a client token (never the passes themselves). Signed out."""
import asyncio, collections, json, sys, time
from urllib.parse import urlsplit
from playwright.async_api import async_playwright

PHONE = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
DESK = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"


async def visit(p, name, phone):
    b = await p.chromium.launch(args=["--disable-blink-features=AutomationControlled"])
    kw = dict(p.devices["Pixel 7"]) if phone else {"viewport": {"width": 1280, "height": 800}}
    kw.update(user_agent=PHONE if phone else DESK, service_workers="allow", locale="en-IN")
    ctx = await b.new_context(**kw)
    seen, said, t0 = [], [], time.time()

    async def note(r, round_):
        try:
            h = await r.all_headers()
        except Exception:
            h = r.headers
        u = urlsplit(r.url)
        if "spotify" not in u.netloc:
            return
        seen.append(dict(round=round_, t=round(time.time() - t0, 1), host=u.netloc, path=u.path[:70], method=r.method,
                         kind=r.resource_type, worker=getattr(r, "service_worker", None) is not None,
                         bearer=h.get("authorization", "").startswith("Bearer "), client_token="client-token" in h))

    state = {"round": 1}
    ctx.on("request", lambda r: asyncio.ensure_future(note(r, state["round"])))
    page = await ctx.new_page()
    page.on("console", lambda m: said.append(m.text[:160]))
    await page.goto("https://open.spotify.com/", wait_until="domcontentloaded", timeout=60000)
    await asyncio.sleep(25)
    await page.screenshot(path=f"out/{name}-1.png")
    text1 = (await page.evaluate("document.body ? document.body.innerText : ''"))[:500]
    sw = await page.evaluate("""async () => {
        const r = await navigator.serviceWorker.getRegistrations();
        return {controlled: !!navigator.serviceWorker.controller, scripts: r.map(x => (x.active || x.installing || x.waiting || {}).scriptURL)} }""")
    src = ""
    for s in sw.get("scripts") or []:
        if s:
            src = await page.evaluate("u => fetch(u).then(r => r.text()).catch(e => String(e))", s)
            break
    state["round"] = 2
    t0 = time.time()
    await page.reload(wait_until="domcontentloaded", timeout=60000)
    await asyncio.sleep(20)
    await page.screenshot(path=f"out/{name}-2.png")
    controlled2 = await page.evaluate("!!navigator.serviceWorker.controller")
    await b.close()

    print(f"\n===== {name} =====")
    print("page says:", " | ".join(text1.split("\n")[:12]))
    print("service worker:", sw, "| controls the page after a reload:", controlled2, "| its script:", len(src), "chars")
    for k in ("addEventListener(\"fetch\"", "addEventListener('fetch'", "onfetch", "api-partner", "spclient", "pathfinder", "respondWith"):
        print(f"   sw has {k!r}: {k in src}")
    for r_ in (1, 2):
        rows = [s for s in seen if s["round"] == r_]
        c = collections.Counter((s["host"], s["worker"], s["bearer"], s["client_token"]) for s in rows)
        print(f"-- round {r_}: {len(rows)} asks of spotify hosts. (host, by worker, bearer, client token): count")
        for k, v in sorted(c.items(), key=lambda kv: -kv[1]):
            print("  ", k, v)
        first = [s for s in rows if s["bearer"]][:8]
        print("   first with a bearer:")
        for s in first:
            print("    ", json.dumps(s))
        api = [s for s in rows if "pathfinder" in s["path"] or "token" in s["path"]][:12]
        print("   token/pathfinder asks:")
        for s in api:
            print("    ", json.dumps(s))
    print("console:", said[:15])


async def main():
    async with async_playwright() as p:
        for name, phone in (("phone", True), ("computer", False)):
            try:
                await visit(p, name, phone)
            except Exception as e:
                print(name, "failed:", repr(e))


asyncio.run(main())
