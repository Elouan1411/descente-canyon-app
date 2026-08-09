import { NextRequest, NextResponse } from 'next/server';
import { generateClientTokenFromReadWriteToken } from '@vercel/blob/client';
import { verifyHmacAuth } from '@/lib/security';

export async function POST(
  req: NextRequest,
  { params }: { params: { canyonId: string } }
) {
  if (!verifyHmacAuth(req)) {
    return NextResponse.json({ error: 'Unauthorized APK signature' }, { status: 403 });
  }

  const canyonId = parseInt(params.canyonId, 10);
  if (isNaN(canyonId)) {
    return NextResponse.json({ error: 'Invalid canyon ID' }, { status: 400 });
  }

  try {
    const body = await req.json().catch(() => ({}));
    const fileName = body.fileName || 'file';
    const pdfId = `pdf_${Date.now()}_${Math.random().toString(36).substring(2, 9)}`;
    const cleanFileName = fileName.replace(/[^a-zA-Z0-9_.-]/g, '_');
    const pathname = `canyons/${canyonId}/${pdfId}_${cleanFileName}`;

    const clientToken = await generateClientTokenFromReadWriteToken({
      pathname,
      token: process.env.BLOB_READ_WRITE_TOKEN,
      maximumSizeInBytes: 104857600, // 100MB
    });

    return NextResponse.json({
      pdfId,
      pathname,
      clientToken,
      uploadUrl: `https://blob.vercel-storage.com/${pathname}`,
    });
  } catch (error) {
    console.error('Error generating Blob client token:', error);
    return NextResponse.json({ error: 'Failed to generate upload token' }, { status: 500 });
  }
}
