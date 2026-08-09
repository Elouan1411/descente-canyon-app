import { NextRequest, NextResponse } from 'next/server';
import { put } from '@vercel/blob';
import { verifyHmacAuth } from '@/lib/security';
import { getPdfsForCanyon, insertPdfRecord, CanyonPdfRecord } from '@/lib/db';

export async function GET(
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

  const pdfs = await getPdfsForCanyon(canyonId);
  return NextResponse.json({ pdfs });
}

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

  const formData = await req.formData();
  const file = formData.get('file') as File | null;

  if (!file) {
    return NextResponse.json({ error: 'No PDF file provided' }, { status: 400 });
  }

  // 100 MB limit in bytes = 100 * 1024 * 1024 = 104857600
  if (file.size > 104857600) {
    return NextResponse.json(
      { error: 'File size exceeds 100MB limit' },
      { status: 400 }
    );
  }

  const pdfId = `pdf_${Date.now()}_${Math.random().toString(36).substring(2, 9)}`;
  const pathname = `canyons/${canyonId}/${pdfId}_${file.name}`;

  const blob = await put(pathname, file, {
    access: 'public',
  });

  const record: CanyonPdfRecord = {
    id: pdfId,
    canyonId,
    fileName: file.name,
    fileSize: file.size,
    blobUrl: blob.url,
    uploadedAt: Date.now(),
  };

  await insertPdfRecord(record);

  return NextResponse.json({ pdf: record }, { status: 201 });
}
