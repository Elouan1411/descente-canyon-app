"use strict";(()=>{var e={};e.id=327,e.ids=[327],e.modules={399:e=>{e.exports=require("next/dist/compiled/next-server/app-page.runtime.prod.js")},517:e=>{e.exports=require("next/dist/compiled/next-server/app-route.runtime.prod.js")},7790:e=>{e.exports=require("assert")},1212:e=>{e.exports=require("async_hooks")},8893:e=>{e.exports=require("buffer")},3199:e=>{e.exports=require("console")},4770:e=>{e.exports=require("crypto")},7920:e=>{e.exports=require("diagnostics_channel")},7702:e=>{e.exports=require("events")},2048:e=>{e.exports=require("fs")},2615:e=>{e.exports=require("http")},2694:e=>{e.exports=require("http2")},5240:e=>{e.exports=require("https")},8216:e=>{e.exports=require("net")},9801:e=>{e.exports=require("os")},5315:e=>{e.exports=require("path")},6119:e=>{e.exports=require("perf_hooks")},6624:e=>{e.exports=require("querystring")},6162:e=>{e.exports=require("stream")},6083:e=>{e.exports=require("stream/web")},4026:e=>{e.exports=require("string_decoder")},2452:e=>{e.exports=require("tls")},7360:e=>{e.exports=require("url")},1764:e=>{e.exports=require("util")},1814:e=>{e.exports=require("util/types")},2623:e=>{e.exports=require("worker_threads")},1568:e=>{e.exports=require("zlib")},6005:e=>{e.exports=require("node:crypto")},5673:e=>{e.exports=require("node:events")},4492:e=>{e.exports=require("node:stream")},7261:e=>{e.exports=require("node:util")},7075:(e,r,t)=>{t.r(r),t.d(r,{originalPathname:()=>m,patchFetch:()=>b,requestAsyncStorage:()=>c,routeModule:()=>_,serverHooks:()=>N,staticGenerationAsyncStorage:()=>f});var o={};t.r(o),t.d(o,{DELETE:()=>p});var i=t(9303),n=t(8716),a=t(670),s=t(7070),d=t(8863),u=t(8605),l=t(9487);async function p(e,{params:r}){if(!(0,u.A)(e))return s.NextResponse.json({error:"Unauthorized APK signature"},{status:403});let{pdfId:t}=r;if(!t)return s.NextResponse.json({error:"Missing pdfId parameter"},{status:400});let o=await (0,l.$6)(t);if(!o)return s.NextResponse.json({error:"PDF not found"},{status:404});try{await (0,d.IV)(o.blobUrl)}catch(e){console.error("Failed to delete blob from Vercel storage:",e)}return s.NextResponse.json({success:!0,deletedId:t})}let _=new i.AppRouteRouteModule({definition:{kind:n.x.APP_ROUTE,page:"/api/pdfs/[pdfId]/route",pathname:"/api/pdfs/[pdfId]",filename:"route",bundlePath:"app/api/pdfs/[pdfId]/route"},resolvedPagePath:"/home/elouan/Documents/others/projet_info_perso/android/android-studio/descente-canyon-app/backend/app/api/pdfs/[pdfId]/route.ts",nextConfigOutput:"",userland:o}),{requestAsyncStorage:c,staticGenerationAsyncStorage:f,serverHooks:N}=_,m="/api/pdfs/[pdfId]/route";function b(){return(0,a.patchFetch)({serverHooks:N,staticGenerationAsyncStorage:f})}},9487:(e,r,t)=>{t.d(r,{$6:()=>s,C_:()=>d,Eo:()=>a,HK:()=>l,o6:()=>n,yC:()=>u});var o=t(8462);async function i(){try{await (0,o.i6)`
      CREATE TABLE IF NOT EXISTS canyon_pdfs (
        id VARCHAR(255) PRIMARY KEY,
        canyon_id INT NOT NULL,
        file_name VARCHAR(255) NOT NULL,
        file_size BIGINT NOT NULL,
        blob_url TEXT NOT NULL,
        uploaded_at BIGINT NOT NULL
      );
    `,await (0,o.i6)`
      CREATE INDEX IF NOT EXISTS idx_canyon_id ON canyon_pdfs(canyon_id);
    `,await (0,o.i6)`
      CREATE TABLE IF NOT EXISTS app_releases (
        id VARCHAR(255) PRIMARY KEY,
        version_code INT NOT NULL,
        version_name VARCHAR(100) NOT NULL,
        release_notes TEXT NOT NULL,
        blob_url TEXT NOT NULL,
        uploaded_at BIGINT NOT NULL,
        min_supported_version_code INT NOT NULL DEFAULT 1
      );
    `,await (0,o.i6)`
      CREATE INDEX IF NOT EXISTS idx_version_code ON app_releases(version_code);
    `}catch(e){console.error("Error initializing Postgres DB:",e)}}async function n(e){try{let{rows:r}=await (0,o.i6)`
      SELECT id, canyon_id, file_name, file_size, blob_url, uploaded_at 
      FROM canyon_pdfs 
      WHERE canyon_id = ${e}
      ORDER BY uploaded_at DESC
    `;return r.map(e=>({id:e.id,canyonId:Number(e.canyon_id),fileName:e.file_name,fileSize:Number(e.file_size),blobUrl:e.blob_url,uploadedAt:Number(e.uploaded_at)}))}catch(e){return await i(),[]}}async function a(e){await i(),await (0,o.i6)`
    INSERT INTO canyon_pdfs (id, canyon_id, file_name, file_size, blob_url, uploaded_at)
    VALUES (${e.id}, ${e.canyonId}, ${e.fileName}, ${e.fileSize}, ${e.blobUrl}, ${e.uploadedAt})
  `}async function s(e){try{let{rows:r}=await (0,o.i6)`
      DELETE FROM canyon_pdfs 
      WHERE id = ${e}
      RETURNING id, canyon_id, file_name, file_size, blob_url, uploaded_at
    `;if(0===r.length)return null;let t=r[0];return{id:t.id,canyonId:Number(t.canyon_id),fileName:t.file_name,fileSize:Number(t.file_size),blobUrl:t.blob_url,uploadedAt:Number(t.uploaded_at)}}catch(e){return null}}async function d(){try{let{rows:e}=await (0,o.i6)`
      SELECT id, version_code, version_name, release_notes, blob_url, uploaded_at, min_supported_version_code
      FROM app_releases
      ORDER BY version_code DESC
      LIMIT 1
    `;if(0===e.length)return null;let r=e[0];return{id:r.id,versionCode:Number(r.version_code),versionName:r.version_name,releaseNotes:r.release_notes,blobUrl:r.blob_url,uploadedAt:Number(r.uploaded_at),minSupportedVersionCode:Number(r.min_supported_version_code)}}catch(e){return await i(),null}}async function u(e){await i(),await (0,o.i6)`
    INSERT INTO app_releases (id, version_code, version_name, release_notes, blob_url, uploaded_at, min_supported_version_code)
    VALUES (${e.id}, ${e.versionCode}, ${e.versionName}, ${e.releaseNotes}, ${e.blobUrl}, ${e.uploadedAt}, ${e.minSupportedVersionCode})
  `}async function l(e){try{let{rows:r}=await (0,o.i6)`
      DELETE FROM app_releases
      WHERE id != ${e}
      RETURNING id, version_code, version_name, release_notes, blob_url, uploaded_at, min_supported_version_code
    `;return r.map(e=>({id:e.id,versionCode:Number(e.version_code),versionName:e.version_name,releaseNotes:e.release_notes,blobUrl:e.blob_url,uploadedAt:Number(e.uploaded_at),minSupportedVersionCode:Number(e.min_supported_version_code)}))}catch(e){return[]}}},8605:(e,r,t)=>{t.d(r,{A:()=>a});var o=t(4770),i=t.n(o);let n=(process.env.APP_SECRET||"descente_canyon_secret_key_2026").trim();function a(e){if("true"===process.env.SKIP_AUTH)return!0;let r=e.headers.get("x-app-auth");if(!r)return!1;try{let t=r.split(":");if(3!==t.length)return!1;let[o,a,s]=t,d=parseInt(o,10),u=Date.now();if(Math.abs(u-d)>6e5)return!1;for(let r of(process.env.APK_SIGNATURE_HASH||"default_apk_sha256_hash").split(",").map(e=>e.replace(/:/g,"").trim().toLowerCase()).filter(Boolean)){let t=`${o}:${a}:${r}:${e.nextUrl.pathname}`,d=i().createHmac("sha256",n).update(t).digest("hex");if(s.length===d.length&&i().timingSafeEqual(Buffer.from(s,"hex"),Buffer.from(d,"hex")))return!0}return!1}catch(e){return!1}}}};var r=require("../../../../webpack-runtime.js");r.C(e);var t=e=>r(r.s=e),o=r.X(0,[407,863],()=>t(7075));module.exports=o})();