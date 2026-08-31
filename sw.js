const CACHE="riftos-shell-v26-riftwebkit-embedded-host";
// Keep the expensive WebKit payload independent from fast-moving RiftOS shell
// revisions. Bump this only when the published mobile engine build changes.
const ENGINE_CACHE="riftwebkit-engine-c6126db7";
const CORE=[
  "./",
  "./index.html",
  "./styles.css",
  "./src/riftcore.js",
  "./src/riftworkspace-web.js",
  "./src/riftruntime.js",
  "./src/riftbrowser-engines.js",
  "./src/riftbrowser-kernel.js",
  "./src/riftbrowser-ui.js",
  "./src/riftbrowser-ui.css",
  "./src/riftos.js",
  "./src/riftapps.js",
  "./src/riftapps-files.js",
  "./src/riftgit.js",
  "./src/riftdev-host.js",
  "./manifest.webmanifest"
];

const coreURLs=new Set(CORE.map(path=>new URL(path,self.registration.scope).href));

function isolateResponse(response){
  if(!response||response.type==="opaque")return response;
  const headers=new Headers(response.headers);
  headers.set("Cross-Origin-Opener-Policy","same-origin");
  headers.set("Cross-Origin-Embedder-Policy","require-corp");
  headers.set("Cross-Origin-Resource-Policy","same-origin");
  return new Response(response.body,{status:response.status,statusText:response.statusText,headers});
}
async function networkIsolated(request,options){return isolateResponse(await fetch(request,options));}

async function webKitHostResponse(request,url){
  const raw=await fetch(request,{cache:"no-cache"});
  if(!raw.ok||url.searchParams.get("embed")!=="1"||!url.pathname.endsWith("/engines/webkit/index.html"))return isolateResponse(raw);
  let html=await raw.text();
  const marker="riftos-embedded-webkit-style";
  if(!html.includes(marker)&&html.includes("</head>")){
    html=html.replace("</head>",`<style id="${marker}">#bibfreeze{display:none!important}</style></head>`);
  }
  const headers=new Headers(raw.headers);
  headers.delete("content-length");
  headers.set("content-type","text/html; charset=utf-8");
  return isolateResponse(new Response(html,{status:raw.status,statusText:raw.statusText,headers}));
}

self.addEventListener("install",event=>event.waitUntil((async()=>{
  const cache=await caches.open(CACHE);
  for(const path of CORE){
    const request=new Request(new URL(path,self.registration.scope),{cache:"reload"});
    const response=await networkIsolated(request);
    if(!response.ok)throw new Error(`RiftOS core cache failed: ${path} HTTP ${response.status}`);
    await cache.put(request,response);
  }
  await self.skipWaiting();
})()));

self.addEventListener("activate",event=>event.waitUntil(
  caches.keys().then(keys=>Promise.all(keys.filter(key=>key!==CACHE&&key!==ENGINE_CACHE).map(key=>caches.delete(key)))).then(()=>self.clients.claim())
));

self.addEventListener("fetch",event=>{
  const request=event.request;if(request.method!=="GET")return;
  const url=new URL(request.url);
  if(url.origin!==location.origin){event.respondWith(fetch(request));return;}

  const isCore=coreURLs.has(url.href);
  const isRiftDev=url.pathname.includes("/apps/riftdev/");
  const isBrowserEngine=url.pathname.includes("/engines/webkit/");
  const isEngineHost=isBrowserEngine&&url.pathname.endsWith("/engines/webkit/index.html");
  const isEngineHeavy=isBrowserEngine&&(
    url.pathname.includes("/engines/webkit/engine/")||
    url.pathname.includes("/engines/webkit/vendor/")||
    url.pathname.endsWith("/engines/webkit/wasm-polyfill.js")||
    url.pathname.endsWith("/engines/webkit/media-stub.js")
  );

  if(isCore){
    event.respondWith((async()=>{
      try{
        const response=await networkIsolated(request,{cache:"no-cache"});
        if(response.ok){const cache=await caches.open(CACHE);await cache.put(request,response.clone());}
        return response;
      }catch{
        const cached=await caches.match(request);
        return cached?isolateResponse(cached):new Response("Offline RiftKernel asset unavailable",{status:503});
      }
    })());return;
  }

  // The WASM/JS/vendor payload is immutable for this engine build and very
  // large. Cache-first avoids re-fetching/revalidating it after ordinary shell
  // updates. The manifest + host HTML remain network-first so runtime fixes can
  // ship instantly without rebuilding WebKit.
  if(isEngineHeavy){
    event.respondWith((async()=>{
      const cache=await caches.open(ENGINE_CACHE);
      const cached=await cache.match(request);
      if(cached)return isolateResponse(cached);
      try{
        const response=await networkIsolated(request,{cache:"default"});
        if(response.ok)await cache.put(request,response.clone());
        return response;
      }catch{
        return new Response("RiftWebKit engine asset unavailable",{status:503});
      }
    })());return;
  }

  if(isRiftDev||isBrowserEngine){
    event.respondWith((async()=>{
      const cache=await caches.open(CACHE);
      try{
        const response=isEngineHost?await webKitHostResponse(request,url):await networkIsolated(request,{cache:"no-cache"});
        if(response.ok)await cache.put(request,response.clone());
        return response;
      }catch{
        const cached=await cache.match(request);
        return cached?isolateResponse(cached):new Response(isBrowserEngine?"RiftWebKit engine artifact unavailable":"Offline RiftDev asset unavailable",{status:503});
      }
    })());return;
  }

  event.respondWith((async()=>{
    try{return await networkIsolated(request);}catch{
      if(request.mode==="navigate"){
        const shell=await caches.match(new URL("./index.html",self.registration.scope).href);
        if(shell)return isolateResponse(shell);
      }
      return new Response("Offline asset unavailable",{status:503,statusText:"Offline asset unavailable"});
    }
  })());
});
