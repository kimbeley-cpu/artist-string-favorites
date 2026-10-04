// 先走网络拿最新版；没网时才用上次存下的页面，所以不会卡在旧版本
const C="asf-shell-v1";
self.addEventListener("install",e=>self.skipWaiting());
self.addEventListener("activate",e=>e.waitUntil(self.clients.claim()));
self.addEventListener("fetch",e=>{
  const r=e.request;if(r.method!=="GET"||new URL(r.url).origin!==location.origin)return;
  e.respondWith(fetch(r,{cache:"no-store"}).then(res=>{if(res.ok){const c=res.clone();caches.open(C).then(k=>k.put(r,c))}return res})
    .catch(()=>caches.match(r,{ignoreSearch:true}).then(m=>m||Response.error())))});
