import { NextRequest, NextResponse } from 'next/server';
import { put, del, list } from '@vercel/blob';
import { verifyHmacAuth } from '@/lib/security';
import { getLatestRelease, insertReleaseRecord, deleteOldReleasesExcept, AppReleaseRecord } from '@/lib/db';

export async function GET(req: NextRequest) {
  if (!verifyHmacAuth(req)) {
    return NextResponse.json({ error: 'Unauthorized APK signature' }, { status: 403 });
  }

  const latest = await getLatestRelease();
  if (!latest) {
    return NextResponse.json({ message: 'No release available' }, { status: 404 });
  }

  return NextResponse.json({
    versionCode: latest.versionCode,
    versionName: latest.versionName,
    releaseNotes: latest.releaseNotes,
    minSupportedVersionCode: latest.minSupportedVersionCode,
    uploadedAt: latest.uploadedAt,
    downloadUrl: '/api/app/update/download',
  });
}

export async function POST(req: NextRequest) {
  if (!verifyHmacAuth(req)) {
    return NextResponse.json({ error: 'Unauthorized APK signature' }, { status: 403 });
  }

  try {
    const contentType = req.headers.get('content-type') || '';
    let finalBlobUrl = '';
    let versionCode = 0;
    let versionName = '1.0.0';
    let releaseNotes = 'Nouvelle version disponible.';
    let minSupportedVersionCode = 1;

    if (contentType.includes('application/json')) {
      const json = await req.json();
      finalBlobUrl = json.blobUrl || '';
      versionCode = parseInt(json.versionCode, 10);
      if (json.versionName) versionName = json.versionName;
      if (json.releaseNotes) releaseNotes = json.releaseNotes;
      if (json.minSupportedVersionCode) minSupportedVersionCode = parseInt(json.minSupportedVersionCode, 10);

      if (!finalBlobUrl || isNaN(versionCode)) {
        return NextResponse.json({ error: 'Missing blobUrl or valid versionCode' }, { status: 400 });
      }
    } else {
      const formData = await req.formData();
      const file = formData.get('file') as File | null;
      const versionCodeStr = formData.get('versionCode') as string | null;
      const vName = formData.get('versionName') as string | null;
      const rNotes = formData.get('releaseNotes') as string | null;
      const minSupportedStr = formData.get('minSupportedVersionCode') as string | null;

      if (!file || !versionCodeStr) {
        return NextResponse.json({ error: 'Missing file or versionCode' }, { status: 400 });
      }

      versionCode = parseInt(versionCodeStr, 10);
      if (isNaN(versionCode)) {
        return NextResponse.json({ error: 'Invalid versionCode' }, { status: 400 });
      }

      if (vName) versionName = vName;
      if (rNotes) releaseNotes = rNotes;
      if (minSupportedStr) minSupportedVersionCode = parseInt(minSupportedStr, 10);

      // Upload to Vercel Blob
      const blobFilename = `releases/descente-canyon-v${versionCode}-${Date.now()}.apk`;
      const blob = await put(blobFilename, file, { access: 'public' });
      finalBlobUrl = blob.url;
    }

    const releaseId = `rel_${versionCode}_${Date.now()}`;
    const newRelease: AppReleaseRecord = {
      id: releaseId,
      versionCode,
      versionName,
      releaseNotes,
      blobUrl: finalBlobUrl,
      uploadedAt: Date.now(),
      minSupportedVersionCode,
    };

    await insertReleaseRecord(newRelease);

    // Clean up old releases from DB
    await deleteOldReleasesExcept(releaseId);

    // Purge ALL old & orphan release blobs in Vercel Blob under 'releases/'
    try {
      const { blobs } = await list({ prefix: 'releases/' });
      for (const b of blobs) {
        if (b.url !== finalBlobUrl) {
          try {
            await del(b.url);
            console.log(`Deleted old release blob: ${b.url}`);
          } catch (e) {
            console.error(`Failed to delete old blob ${b.url}:`, e);
          }
        }
      }
    } catch (e) {
      console.error('Failed to list/purge old release blobs:', e);
    }

    return NextResponse.json({ success: true, release: newRelease });
  } catch (err) {
    console.error('Error uploading app release:', err);
    return NextResponse.json({ error: 'Internal Server Error' }, { status: 500 });
  }
}
