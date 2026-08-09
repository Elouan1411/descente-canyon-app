"use strict";(()=>{var e={};e.id=171,e.ids=[171],e.modules={399:e=>{e.exports=require("next/dist/compiled/next-server/app-page.runtime.prod.js")},517:e=>{e.exports=require("next/dist/compiled/next-server/app-route.runtime.prod.js")},8893:e=>{e.exports=require("buffer")},4770:e=>{e.exports=require("crypto")},7702:e=>{e.exports=require("events")},2048:e=>{e.exports=require("fs")},2615:e=>{e.exports=require("http")},5240:e=>{e.exports=require("https")},8216:e=>{e.exports=require("net")},9801:e=>{e.exports=require("os")},5315:e=>{e.exports=require("path")},6162:e=>{e.exports=require("stream")},2452:e=>{e.exports=require("tls")},7360:e=>{e.exports=require("url")},1568:e=>{e.exports=require("zlib")},511:(e,r,o)=>{o.r(r),o.d(r,{originalPathname:()=>m,patchFetch:()=>f,requestAsyncStorage:()=>_,routeModule:()=>p,serverHooks:()=>N,staticGenerationAsyncStorage:()=>c});var a={};o.r(a),o.d(a,{GET:()=>u});var t=o(9303),n=o(8716),i=o(670),s=o(7070),d=o(8605),l=o(9487);async function u(e){if(!(0,d.A)(e))return s.NextResponse.json({error:"Unauthorized APK signature"},{status:403});let r=await (0,l.C_)();return r&&r.blobUrl?s.NextResponse.redirect(r.blobUrl):s.NextResponse.json({error:"No release binary found"},{status:404})}let p=new t.AppRouteRouteModule({definition:{kind:n.x.APP_ROUTE,page:"/api/app/update/download/route",pathname:"/api/app/update/download",filename:"route",bundlePath:"app/api/app/update/download/route"},resolvedPagePath:"/home/elouan/Documents/others/projet_info_perso/android/android-studio/descente-canyon-app/backend/app/api/app/update/download/route.ts",nextConfigOutput:"",userland:a}),{requestAsyncStorage:_,staticGenerationAsyncStorage:c,serverHooks:N}=p,m="/api/app/update/download/route";function f(){return(0,i.patchFetch)({serverHooks:N,staticGenerationAsyncStorage:c})}},9487:(e,r,o)=>{o.d(r,{$6:()=>s,C_:()=>d,Eo:()=>i,HK:()=>u,o6:()=>n,yC:()=>l});var a=o(8462);async function t(){try{await (0,a.i6)`
      CREATE TABLE IF NOT EXISTS canyon_pdfs (
        id VARCHAR(255) PRIMARY KEY,
        canyon_id INT NOT NULL,
        file_name VARCHAR(255) NOT NULL,
        file_size BIGINT NOT NULL,
        blob_url TEXT NOT NULL,
        uploaded_at BIGINT NOT NULL
      );
    `,await (0,a.i6)`
      CREATE INDEX IF NOT EXISTS idx_canyon_id ON canyon_pdfs(canyon_id);
    `,await (0,a.i6)`
      CREATE TABLE IF NOT EXISTS app_releases (
        id VARCHAR(255) PRIMARY KEY,
        version_code INT NOT NULL,
        version_name VARCHAR(100) NOT NULL,
        release_notes TEXT NOT NULL,
        blob_url TEXT NOT NULL,
        uploaded_at BIGINT NOT NULL,
        min_supported_version_code INT NOT NULL DEFAULT 1
      );
    `,await (0,a.i6)`
      CREATE INDEX IF NOT EXISTS idx_version_code ON app_releases(version_code);
    `}catch(e){console.error("Error initializing Postgres DB:",e)}}async function n(e){try{let{rows:r}=await (0,a.i6)`
      SELECT id, canyon_id, file_name, file_size, blob_url, uploaded_at 
      FROM canyon_pdfs 
      WHERE canyon_id = ${e}
      ORDER BY uploaded_at DESC
    `;return r.map(e=>({id:e.id,canyonId:Number(e.canyon_id),fileName:e.file_name,fileSize:Number(e.file_size),blobUrl:e.blob_url,uploadedAt:Number(e.uploaded_at)}))}catch(e){return await t(),[]}}async function i(e){await t(),await (0,a.i6)`
    INSERT INTO canyon_pdfs (id, canyon_id, file_name, file_size, blob_url, uploaded_at)
    VALUES (${e.id}, ${e.canyonId}, ${e.fileName}, ${e.fileSize}, ${e.blobUrl}, ${e.uploadedAt})
  `}async function s(e){try{let{rows:r}=await (0,a.i6)`
      DELETE FROM canyon_pdfs 
      WHERE id = ${e}
      RETURNING id, canyon_id, file_name, file_size, blob_url, uploaded_at
    `;if(0===r.length)return null;let o=r[0];return{id:o.id,canyonId:Number(o.canyon_id),fileName:o.file_name,fileSize:Number(o.file_size),blobUrl:o.blob_url,uploadedAt:Number(o.uploaded_at)}}catch(e){return null}}async function d(){try{let{rows:e}=await (0,a.i6)`
      SELECT id, version_code, version_name, release_notes, blob_url, uploaded_at, min_supported_version_code
      FROM app_releases
      ORDER BY version_code DESC
      LIMIT 1
    `;if(0===e.length)return null;let r=e[0];return{id:r.id,versionCode:Number(r.version_code),versionName:r.version_name,releaseNotes:r.release_notes,blobUrl:r.blob_url,uploadedAt:Number(r.uploaded_at),minSupportedVersionCode:Number(r.min_supported_version_code)}}catch(e){return await t(),null}}async function l(e){await t(),await (0,a.i6)`
    INSERT INTO app_releases (id, version_code, version_name, release_notes, blob_url, uploaded_at, min_supported_version_code)
    VALUES (${e.id}, ${e.versionCode}, ${e.versionName}, ${e.releaseNotes}, ${e.blobUrl}, ${e.uploadedAt}, ${e.minSupportedVersionCode})
  `}async function u(e){try{let{rows:r}=await (0,a.i6)`
      DELETE FROM app_releases
      WHERE id != ${e}
      RETURNING id, version_code, version_name, release_notes, blob_url, uploaded_at, min_supported_version_code
    `;return r.map(e=>({id:e.id,versionCode:Number(e.version_code),versionName:e.version_name,releaseNotes:e.release_notes,blobUrl:e.blob_url,uploadedAt:Number(e.uploaded_at),minSupportedVersionCode:Number(e.min_supported_version_code)}))}catch(e){return[]}}},8605:(e,r,o)=>{o.d(r,{A:()=>i});var a=o(4770),t=o.n(a);let n=(process.env.APP_SECRET||"descente_canyon_secret_key_2026").trim();function i(e){if("true"===process.env.SKIP_AUTH)return!0;let r=e.headers.get("x-app-auth");if(!r)return!1;try{let o=r.split(":");if(3!==o.length)return!1;let[a,i,s]=o,d=parseInt(a,10),l=Date.now();if(Math.abs(l-d)>6e5)return!1;for(let r of(process.env.APK_SIGNATURE_HASH||"default_apk_sha256_hash").split(",").map(e=>e.replace(/:/g,"").trim().toLowerCase()).filter(Boolean)){let o=`${a}:${i}:${r}:${e.nextUrl.pathname}`,d=t().createHmac("sha256",n).update(o).digest("hex");if(s.length===d.length&&t().timingSafeEqual(Buffer.from(s,"hex"),Buffer.from(d,"hex")))return!0}return!1}catch(e){return!1}}}};var r=require("../../../../../webpack-runtime.js");r.C(e);var o=e=>r(r.s=e),a=r.X(0,[407],()=>o(511));module.exports=a})();