import { NextRequest, NextResponse } from 'next/server';
import { del } from '@vercel/blob';
import { verifyHmacAuth } from '@/lib/security';
import { deletePdfRecord } from '@/lib/db';

export async function DELETE(
  req: NextRequest,
  { params }: { params: { pdfId: string } }
) {
  if (!verifyHmacAuth(req)) {
    return NextResponse.json({ error: 'Unauthorized APK signature' }, { status: 403 });
  }

  const { pdfId } = params;
  if (!pdfId) {
    return NextResponse.json({ error: 'Missing pdfId parameter' }, { status: 400 });
  }

  const deletedRecord = await deletePdfRecord(pdfId);
  if (!deletedRecord) {
    return NextResponse.json({ error: 'PDF not found' }, { status: 404 });
  }

  try {
    await del(deletedRecord.blobUrl);
  } catch (err) {
    console.error('Failed to delete blob from Vercel storage:', err);
  }

  return NextResponse.json({ success: true, deletedId: pdfId });
}
