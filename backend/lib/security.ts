import crypto from 'crypto';
import { NextRequest } from 'next/server';

const APP_SECRET = (process.env.APP_SECRET || 'descente_canyon_secret_key_2026').trim();

export function verifyHmacAuth(req: NextRequest): boolean {
  if (process.env.SKIP_AUTH === 'true') {
    return true;
  }

  const authHeader = req.headers.get('x-app-auth');
  if (!authHeader) {
    return false;
  }

  try {
    const parts = authHeader.split(':');
    if (parts.length !== 3) {
      return false;
    }

    const [timestampStr, nonce, signature] = parts;
    const timestamp = parseInt(timestampStr, 10);
    const now = Date.now();

    // 10 minutes tolerance window
    if (Math.abs(now - timestamp) > 10 * 60 * 1000) {
      return false;
    }

    const allowedHashes = (process.env.APK_SIGNATURE_HASH || 'default_apk_sha256_hash')
      .split(',')
      .map(h => h.replace(/:/g, '').trim().toLowerCase())
      .filter(Boolean);

    for (const apkHash of allowedHashes) {
      const payload = `${timestampStr}:${nonce}:${apkHash}:${req.nextUrl.pathname}`;
      const expectedSignature = crypto
        .createHmac('sha256', APP_SECRET)
        .update(payload)
        .digest('hex');

      if (
        signature.length === expectedSignature.length &&
        crypto.timingSafeEqual(Buffer.from(signature, 'hex'), Buffer.from(expectedSignature, 'hex'))
      ) {
        return true;
      }
    }

    return false;
  } catch (error) {
    return false;
  }
}
