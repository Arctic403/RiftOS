const CACHE="riftos-shell-v16-trueos-refactor";
const CORE=[
  "./",
  "./index.html",
  "./styles.css",
  "./src/riftcore.js",
  "./src/riftos.js",
  "./src/riftapps.js",
  "./src/riftapps-files.js",
  "./src/riftgit.js",
  "./src/riftdev-host.js",
  "./manifest.webmanifest"
];

const coreURLs=new Set(CORE.map(path=>new URL(path,self.registration.scope).href));

self.addEventListener("install",event=>event.waitUntil(
  caches.open(CACHE).then(cache=>cache.addAll(CORE)).then(()=>self.skipWaiting())
));

self.addEventListener("activate",event=>event.waitUntil(
  caches.keys()
    .then(keys=>Promise.all(keys.filter(key=>key!==CACHE).map(key=>caches.delete(key))))
    .then(()=>self.clients.claim())
));

self.addEventListener("fetch",event=>{
  const request=event.request;
  if(request.method!=="GET")return;

  const url=new URL(request.url);
  if(url.origin!==location.origin){
    event.respondWith(fetch(request));
    return;
  }

  const isCore=coreURLs.has(url.href);
  const isRiftDev=url.pathname.includes("/apps/riftdev/");

  // Core shell files are network-first so a successful deployment appears
  // immediately. The last known-good copy remains available offline.
  if(isCore){
    event.respondWith((async()=>{
      try{
        const response=await fetch(request,{cache:"no-cache"});
        if(response.ok){
          const cache=await caches.open(CACHE);
          await cache.put(request,response.clone());
        }
        return response;
      }catch{
        return (await caches.match(request)) || new Response("Offline core asset unavailable",{status:503});
      }
    })());
    return;
  }

  // RiftDev is deployed as a pinned clone. Cache successful reads lazily so
  // opening the IDE once makes its static shell available offline.
  if(isRiftDev){
    event.respondWith((async()=>{
      const cache=await caches.open(CACHE);
      try{
        const response=await fetch(request);
        if(response.ok)await cache.put(request,response.clone());
        return response;
      }catch{
        return (await cache.match(request)) || new Response("Offline RiftDev asset unavailable",{status:503});
      }
    })());
    return;
  }

  event.respondWith((async()=>{
    try{return await fetch(request);}
    catch{
      if(request.mode==="navigate"){
        const shell=await caches.match(new URL("./index.html",self.registration.scope).href);
        if(shell)return shell;
      }
      return new Response("Offline asset unavailable",{status:503,statusText:"Offline asset unavailable"});
    }
  })());
});
