import { NextRequest, NextResponse } from 'next/server';
import { verifyHmacAuth } from '@/lib/security';
import { getLatestRelease } from '@/lib/db';

export async function GET(req: NextRequest) {
  if (!verifyHmacAuth(req)) {
    return NextResponse.json({ error: 'Unauthorized APK signature' }, { status: 403 });
  }

  const latest = await getLatestRelease();
  if (!latest || !latest.blobUrl) {
    return NextResponse.json({ error: 'No release binary found' }, { status: 404 });
  }

  // Securely redirect to the Blob URL for download
  return NextResponse.redirect(latest.blobUrl);
}
