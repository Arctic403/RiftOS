const CACHE="riftos-shell-v6-jsc-wasm-fix";
const CORE=["./","./index.html","./styles.css","./src/riftos.js","./manifest.webmanifest"];

self.addEventListener("install",e=>e.waitUntil(
  caches.open(CACHE).then(c=>c.addAll(CORE)).then(()=>self.skipWaiting())
));

self.addEventListener("activate",e=>e.waitUntil(
  caches.keys()
    .then(keys=>Promise.all(keys.filter(k=>k!==CACHE).map(k=>caches.delete(k))))
    .then(()=>self.clients.claim())
));

self.addEventListener("fetch",e=>{
  if(e.request.method!=="GET") return;
  const url=new URL(e.request.url);

  if(url.origin===location.origin){
    e.respondWith(
      fetch(e.request)
        .then(res=>{
          if(res.ok){
            const copy=res.clone();
            caches.open(CACHE).then(c=>c.put(e.request,copy));
          }
          return res;
        })
        .catch(async()=>{
          const hit=await caches.match(e.request);
          if(hit) return hit;

          // Only browser navigations may fall back to the app shell.
          // Returning index.html for .wasm/.js/worker requests makes the
          // WebAssembly compiler receive '<!doctype html>' instead of \0asm.
          if(e.request.mode==="navigate") return caches.match("./index.html");

          return new Response("Offline asset unavailable",{
            status:503,
            statusText:"Offline asset unavailable",
            headers:{"Content-Type":"text/plain; charset=utf-8"}
          });
        })
    );
    return;
  }

  e.respondWith(fetch(e.request).catch(()=>caches.match(e.request)));
});
