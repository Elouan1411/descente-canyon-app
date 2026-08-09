"use strict";(()=>{var e={};e.id=683,e.ids=[683],e.modules={399:e=>{e.exports=require("next/dist/compiled/next-server/app-page.runtime.prod.js")},517:e=>{e.exports=require("next/dist/compiled/next-server/app-route.runtime.prod.js")},7790:e=>{e.exports=require("assert")},1212:e=>{e.exports=require("async_hooks")},8893:e=>{e.exports=require("buffer")},3199:e=>{e.exports=require("console")},4770:e=>{e.exports=require("crypto")},7920:e=>{e.exports=require("diagnostics_channel")},7702:e=>{e.exports=require("events")},2048:e=>{e.exports=require("fs")},2615:e=>{e.exports=require("http")},2694:e=>{e.exports=require("http2")},5240:e=>{e.exports=require("https")},8216:e=>{e.exports=require("net")},9801:e=>{e.exports=require("os")},5315:e=>{e.exports=require("path")},6119:e=>{e.exports=require("perf_hooks")},6624:e=>{e.exports=require("querystring")},6162:e=>{e.exports=require("stream")},6083:e=>{e.exports=require("stream/web")},4026:e=>{e.exports=require("string_decoder")},2452:e=>{e.exports=require("tls")},7360:e=>{e.exports=require("url")},1764:e=>{e.exports=require("util")},1814:e=>{e.exports=require("util/types")},2623:e=>{e.exports=require("worker_threads")},1568:e=>{e.exports=require("zlib")},6005:e=>{e.exports=require("node:crypto")},5673:e=>{e.exports=require("node:events")},4492:e=>{e.exports=require("node:stream")},7261:e=>{e.exports=require("node:util")},4579:(e,r,o)=>{o.r(r),o.d(r,{originalPathname:()=>b,patchFetch:()=>y,requestAsyncStorage:()=>N,routeModule:()=>c,serverHooks:()=>m,staticGenerationAsyncStorage:()=>f});var t={};o.r(t),o.d(t,{GET:()=>p,POST:()=>_});var n=o(9303),a=o(8716),i=o(670),s=o(7070),u=o(8863),d=o(8605),l=o(9487);async function p(e,{params:r}){if(!(0,d.A)(e))return s.NextResponse.json({error:"Unauthorized APK signature"},{status:403});let o=parseInt(r.canyonId,10);if(isNaN(o))return s.NextResponse.json({error:"Invalid canyon ID"},{status:400});let t=await (0,l.o6)(o);return s.NextResponse.json({pdfs:t})}async function _(e,{params:r}){if(!(0,d.A)(e))return s.NextResponse.json({error:"Unauthorized APK signature"},{status:403});let o=parseInt(r.canyonId,10);if(isNaN(o))return s.NextResponse.json({error:"Invalid canyon ID"},{status:400});let t=(await e.formData()).get("file");if(!t)return s.NextResponse.json({error:"No PDF file provided"},{status:400});if(t.size>104857600)return s.NextResponse.json({error:"File size exceeds 100MB limit"},{status:400});let n=`pdf_${Date.now()}_${Math.random().toString(36).substring(2,9)}`,a=`canyons/${o}/${n}_${t.name}`,i=await (0,u.gz)(a,t,{access:"public"}),p={id:n,canyonId:o,fileName:t.name,fileSize:t.size,blobUrl:i.url,uploadedAt:Date.now()};return await (0,l.Eo)(p),s.NextResponse.json({pdf:p},{status:201})}let c=new n.AppRouteRouteModule({definition:{kind:a.x.APP_ROUTE,page:"/api/canyons/[canyonId]/pdfs/route",pathname:"/api/canyons/[canyonId]/pdfs",filename:"route",bundlePath:"app/api/canyons/[canyonId]/pdfs/route"},resolvedPagePath:"/home/elouan/Documents/others/projet_info_perso/android/android-studio/descente-canyon-app/backend/app/api/canyons/[canyonId]/pdfs/route.ts",nextConfigOutput:"",userland:t}),{requestAsyncStorage:N,staticGenerationAsyncStorage:f,serverHooks:m}=c,b="/api/canyons/[canyonId]/pdfs/route";function y(){return(0,i.patchFetch)({serverHooks:m,staticGenerationAsyncStorage:f})}},9487:(e,r,o)=>{o.d(r,{$6:()=>s,C_:()=>u,Eo:()=>i,HK:()=>l,o6:()=>a,yC:()=>d});var t=o(8462);async function n(){try{await (0,t.i6)`
      CREATE TABLE IF NOT EXISTS canyon_pdfs (
        id VARCHAR(255) PRIMARY KEY,
        canyon_id INT NOT NULL,
        file_name VARCHAR(255) NOT NULL,
        file_size BIGINT NOT NULL,
        blob_url TEXT NOT NULL,
        uploaded_at BIGINT NOT NULL
      );
    `,await (0,t.i6)`
      CREATE INDEX IF NOT EXISTS idx_canyon_id ON canyon_pdfs(canyon_id);
    `,await (0,t.i6)`
      CREATE TABLE IF NOT EXISTS app_releases (
        id VARCHAR(255) PRIMARY KEY,
        version_code INT NOT NULL,
        version_name VARCHAR(100) NOT NULL,
        release_notes TEXT NOT NULL,
        blob_url TEXT NOT NULL,
        uploaded_at BIGINT NOT NULL,
        min_supported_version_code INT NOT NULL DEFAULT 1
      );
    `,await (0,t.i6)`
      CREATE INDEX IF NOT EXISTS idx_version_code ON app_releases(version_code);
    `}catch(e){console.error("Error initializing Postgres DB:",e)}}async function a(e){try{let{rows:r}=await (0,t.i6)`
      SELECT id, canyon_id, file_name, file_size, blob_url, uploaded_at 
      FROM canyon_pdfs 
      WHERE canyon_id = ${e}
      ORDER BY uploaded_at DESC
    `;return r.map(e=>({id:e.id,canyonId:Number(e.canyon_id),fileName:e.file_name,fileSize:Number(e.file_size),blobUrl:e.blob_url,uploadedAt:Number(e.uploaded_at)}))}catch(e){return await n(),[]}}async function i(e){await n(),await (0,t.i6)`
    INSERT INTO canyon_pdfs (id, canyon_id, file_name, file_size, blob_url, uploaded_at)
    VALUES (${e.id}, ${e.canyonId}, ${e.fileName}, ${e.fileSize}, ${e.blobUrl}, ${e.uploadedAt})
  `}async function s(e){try{let{rows:r}=await (0,t.i6)`
      DELETE FROM canyon_pdfs 
      WHERE id = ${e}
      RETURNING id, canyon_id, file_name, file_size, blob_url, uploaded_at
    `;if(0===r.length)return null;let o=r[0];return{id:o.id,canyonId:Number(o.canyon_id),fileName:o.file_name,fileSize:Number(o.file_size),blobUrl:o.blob_url,uploadedAt:Number(o.uploaded_at)}}catch(e){return null}}async function u(){try{let{rows:e}=await (0,t.i6)`
      SELECT id, version_code, version_name, release_notes, blob_url, uploaded_at, min_supported_version_code
      FROM app_releases
      ORDER BY version_code DESC
      LIMIT 1
    `;if(0===e.length)return null;let r=e[0];return{id:r.id,versionCode:Number(r.version_code),versionName:r.version_name,releaseNotes:r.release_notes,blobUrl:r.blob_url,uploadedAt:Number(r.uploaded_at),minSupportedVersionCode:Number(r.min_supported_version_code)}}catch(e){return await n(),null}}async function d(e){await n(),await (0,t.i6)`
    INSERT INTO app_releases (id, version_code, version_name, release_notes, blob_url, uploaded_at, min_supported_version_code)
    VALUES (${e.id}, ${e.versionCode}, ${e.versionName}, ${e.releaseNotes}, ${e.blobUrl}, ${e.uploadedAt}, ${e.minSupportedVersionCode})
  `}async function l(e){try{let{rows:r}=await (0,t.i6)`
      DELETE FROM app_releases
      WHERE id != ${e}
      RETURNING id, version_code, version_name, release_notes, blob_url, uploaded_at, min_supported_version_code
    `;return r.map(e=>({id:e.id,versionCode:Number(e.version_code),versionName:e.version_name,releaseNotes:e.release_notes,blobUrl:e.blob_url,uploadedAt:Number(e.uploaded_at),minSupportedVersionCode:Number(e.min_supported_version_code)}))}catch(e){return[]}}},8605:(e,r,o)=>{o.d(r,{A:()=>i});var t=o(4770),n=o.n(t);let a=(process.env.APP_SECRET||"descente_canyon_secret_key_2026").trim();function i(e){if("true"===process.env.SKIP_AUTH)return!0;let r=e.headers.get("x-app-auth");if(!r)return!1;try{let o=r.split(":");if(3!==o.length)return!1;let[t,i,s]=o,u=parseInt(t,10),d=Date.now();if(Math.abs(d-u)>6e5)return!1;for(let r of(process.env.APK_SIGNATURE_HASH||"default_apk_sha256_hash").split(",").map(e=>e.replace(/:/g,"").trim().toLowerCase()).filter(Boolean)){let o=`${t}:${i}:${r}:${e.nextUrl.pathname}`,u=n().createHmac("sha256",a).update(o).digest("hex");if(s.length===u.length&&n().timingSafeEqual(Buffer.from(s,"hex"),Buffer.from(u,"hex")))return!0}return!1}catch(e){return!1}}}};var r=require("../../../../../webpack-runtime.js");r.C(e);var o=e=>r(r.s=e),t=r.X(0,[407,863],()=>o(4579));module.exports=t})();