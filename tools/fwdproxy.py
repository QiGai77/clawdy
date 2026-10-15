# Local unauthenticated HTTP proxy on 127.0.0.1:3128 that forwards to $HTTPS_PROXY with Proxy-Authorization.
import asyncio, os, base64, urllib.parse
u = urllib.parse.urlparse(os.environ.get("HTTPS_PROXY") or os.environ["HTTP_PROXY"])
UH, UP = u.hostname, u.port or 80
AUTH = "Proxy-Authorization: Basic " + base64.b64encode(f"{urllib.parse.unquote(u.username or '')}:{urllib.parse.unquote(u.password or '')}".encode()).decode() + "\r\n"
async def pipe(r, w):
    try:
        while True:
            d = await r.read(65536)
            if not d: break
            w.write(d); await w.drain()
    except Exception: pass
    finally:
        try: w.close()
        except Exception: pass
async def handle(cr, cw):
    try:
        head = await cr.readuntil(b"\r\n\r\n")
    except Exception:
        cw.close(); return
    lines = head.decode("latin1").split("\r\n")
    lines = [l for l in lines if not l.lower().startswith("proxy-authorization")]
    new = lines[0] + "\r\n" + AUTH + "\r\n".join(lines[1:])
    try:
        ur, uw = await asyncio.open_connection(UH, UP)
    except Exception:
        cw.close(); return
    uw.write(new.encode("latin1")); await uw.drain()
    await asyncio.gather(pipe(cr, uw), pipe(ur, cw))
async def main():
    s = await asyncio.start_server(handle, "127.0.0.1", 3128)
    async with s: await s.serve_forever()
asyncio.run(main())
