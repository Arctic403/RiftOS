const CACHE="riftos-shell-v8-safe-cache";
const CORE=[
  "./",
  "./index.html",
  "./styles.css",
  "./src/riftos.js",
  "./src/riftapps.js",
  "./manifest.webmanifest"
];

const coreURLs=new Set(CORE.map(path=>new URL(path,self.registration.scope).href));

self.addEventListener("install",event=>event.waitUntil(
  caches.open(CACHE)
    .then(cache=>cache.addAll(CORE))
    .then(()=>self.skipWaiting())
));

self.addEventListener("activate",event=>event.waitUntil(
  caches.keys()
    .then(keys=>Promise.all(keys.filter(key=>key!==CACHE).map(key=>caches.delete(key))))
    .then(()=>self.clients.claim())
));

self.addEventListener("fetch",event=>{
  const request=event.request;
  if(request.method!=="GET") return;

  const url=new URL(request.url);

  // RiftEngine/JSC/WebCore assets can be tens of MB and may expand much
  // further once WebAssembly is compiled. Never persist them in CacheStorage.
  // They stay network-only so iOS cannot grow the installed PWA to hundreds
  // of MB just by launching/testing the engine.
  if(
    url.origin===location.origin &&
    (url.pathname.endsWith(".wasm") ||
     url.pathname.includes("/riftengine/prebuilt/") ||
     url.pathname.includes("/riftengine/dist/"))
  ){
    event.respondWith(fetch(request));
    return;
  }

  if(url.origin===location.origin){
    const isCore=coreURLs.has(url.href);

    event.respondWith((async()=>{
      try{
        const response=await fetch(request);
        if(response.ok && isCore){
          const cache=await caches.open(CACHE);
          await cache.put(request,response.clone());
        }
        return response;
      }catch(error){
        if(isCore){
          const hit=await caches.match(request);
          if(hit) return hit;
        }

        if(request.mode==="navigate"){
          const shell=await caches.match(new URL("./index.html",self.registration.scope).href);
          if(shell) return shell;
        }

        return new Response("Offline asset unavailable",{
          status:503,
          statusText:"Offline asset unavailable",
          headers:{"Content-Type":"text/plain; charset=utf-8"}
        });
      }
    })());
    return;
  }

  event.respondWith(fetch(request));
});
