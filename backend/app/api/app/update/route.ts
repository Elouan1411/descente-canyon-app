import { NextRequest, NextResponse } from 'next/server';
import { put, del } from '@vercel/blob';
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
    const formData = await req.formData();
    const file = formData.get('file') as File | null;
    const versionCodeStr = formData.get('versionCode') as string | null;
    const versionName = (formData.get('versionName') as string | null) || '1.0.0';
    const releaseNotes = (formData.get('releaseNotes') as string | null) || 'Nouvelle version disponible.';
    const minSupportedVersionCodeStr = formData.get('minSupportedVersionCode') as string | null;

    if (!file || !versionCodeStr) {
      return NextResponse.json({ error: 'Missing file or versionCode' }, { status: 400 });
    }

    const versionCode = parseInt(versionCodeStr, 10);
    const minSupportedVersionCode = minSupportedVersionCodeStr ? parseInt(minSupportedVersionCodeStr, 10) : 1;

    if (isNaN(versionCode)) {
      return NextResponse.json({ error: 'Invalid versionCode' }, { status: 400 });
    }

    // Upload to Vercel Blob
    const blobFilename = `releases/descente-canyon-v${versionCode}-${Date.now()}.apk`;
    const blob = await put(blobFilename, file, { access: 'public' });

    const releaseId = `rel_${versionCode}_${Date.now()}`;
    const newRelease: AppReleaseRecord = {
      id: releaseId,
      versionCode,
      versionName,
      releaseNotes,
      blobUrl: blob.url,
      uploadedAt: Date.now(),
      minSupportedVersionCode,
    };

    await insertReleaseRecord(newRelease);

    // Clean up old releases from DB and Vercel Blob to save storage
    const oldReleases = await deleteOldReleasesExcept(releaseId);
    for (const oldRel of oldReleases) {
      if (oldRel.blobUrl) {
        try {
          await del(oldRel.blobUrl);
        } catch (e) {
          console.error(`Failed to delete old blob ${oldRel.blobUrl}:`, e);
        }
      }
    }

    return NextResponse.json({ success: true, release: newRelease });
  } catch (err) {
    console.error('Error uploading app release:', err);
    return NextResponse.json({ error: 'Internal Server Error' }, { status: 500 });
  }
}
