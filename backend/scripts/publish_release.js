const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

function parseArgs() {
  const args = {};
  process.argv.slice(2).forEach(arg => {
    if (arg.startsWith('--')) {
      const [key, value] = arg.slice(2).split('=');
      args[key] = value || true;
    }
  });
  return args;
}

function loadEnvFiles() {
  ['.env.local', '.env'].forEach(file => {
    const filePath = path.resolve(process.cwd(), file);
    if (fs.existsSync(filePath)) {
      const content = fs.readFileSync(filePath, 'utf-8');
      content.split('\n').forEach(line => {
        const trimmed = line.trim();
        if (trimmed && !trimmed.startsWith('#') && trimmed.includes('=')) {
          const [key, ...valueParts] = trimmed.split('=');
          const val = valueParts.join('=').trim().replace(/^["']|["']$/g, '');
          if (!process.env[key.trim()]) {
            process.env[key.trim()] = val;
          }
        }
      });
    }
  });
}

async function publishRelease() {
  loadEnvFiles();
  const args = parseArgs();

  const apkPath = args.apk;
  const versionCode = args.versionCode;
  const versionName = args.versionName || '1.0.0';
  const notes = args.notes || 'Nouvelle mise à jour disponible.';
  const baseUrl = (args.url || process.env.BACKEND_URL || 'https://descente-canyon-app.vercel.app').replace(/\/$/, '');
  const appSecret = args.secret || process.env.APP_SECRET || 'descente_canyon_secret_key_2026';
  const blobToken = args.blobToken || process.env.BLOB_READ_WRITE_TOKEN;

  if (!apkPath || !versionCode) {
    console.error('Usage: npm run publish-release -- --apk=<path_to_apk> --versionCode=<number> [--versionName=1.4.1] [--notes="Release notes"] [--blobToken=<token>]');
    process.exit(1);
  }

  const absoluteApkPath = path.resolve(apkPath);
  if (!fs.existsSync(absoluteApkPath)) {
    console.error(`Error: APK file not found at ${absoluteApkPath}`);
    process.exit(1);
  }

  console.log(`🚀 Preparing release v${versionName} (build ${versionCode})...`);
  console.log(`📁 File: ${absoluteApkPath}`);
  console.log(`🌐 Target: ${baseUrl}`);

  // Generate HMAC Auth Header
  const timestamp = Date.now().toString();
  const nonce = crypto.randomBytes(8).toString('hex');
  const pathName = '/api/app/update';
  const cleanApkHash = apkHash.split(',')[0].replace(/:/g, '').trim().toLowerCase();
  const payload = `${timestamp}:${nonce}:${cleanApkHash}:${pathName}`;
  const signature = crypto.createHmac('sha256', appSecret).update(payload).digest('hex');
  const authHeader = `${timestamp}:${nonce}:${signature}`;

  try {
    let response;

    if (blobToken) {
      console.log('📦 Uploading APK directly to Vercel Blob (bypassing 4.5MB limit)...');
      const { put } = require('@vercel/blob');
      const blobFilename = `releases/descente-canyon-v${versionCode}-${Date.now()}.apk`;
      const blob = await put(blobFilename, fs.createReadStream(absoluteApkPath), {
        access: 'public',
        token: blobToken,
      });

      console.log(`✅ Uploaded to Blob: ${blob.url}`);
      console.log('📝 Registering release in database...');

      response = await fetch(`${baseUrl}${pathName}`, {
        method: 'POST',
        headers: {
          'x-app-auth': authHeader,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          blobUrl: blob.url,
          versionCode: versionCode.toString(),
          versionName,
          releaseNotes: notes,
        }),
      });
    } else {
      console.log('⚠️ No BLOB_READ_WRITE_TOKEN found. Uploading via Serverless API route (subject to Vercel 4.5MB limit)...');
      const fileBuffer = fs.readFileSync(absoluteApkPath);
      const boundary = '--------------------------' + Math.random().toString(36).substring(2, 15);

      const formFields = [
        { name: 'versionCode', value: versionCode.toString() },
        { name: 'versionName', value: versionName },
        { name: 'releaseNotes', value: notes },
      ];

      let body = Buffer.alloc(0);
      for (const field of formFields) {
        const fieldHeader = `--${boundary}\r\nContent-Disposition: form-data; name="${field.name}"\r\n\r\n${field.value}\r\n`;
        body = Buffer.concat([body, Buffer.from(fieldHeader, 'utf-8')]);
      }

      const fileName = path.basename(absoluteApkPath);
      const fileHeader = `--${boundary}\r\nContent-Disposition: form-data; name="file"; filename="${fileName}"\r\nContent-Type: application/vnd.android.package-archive\r\n\r\n`;
      const fileFooter = `\r\n--${boundary}--\r\n`;

      body = Buffer.concat([
        body,
        Buffer.from(fileHeader, 'utf-8'),
        fileBuffer,
        Buffer.from(fileFooter, 'utf-8')
      ]);

      response = await fetch(`${baseUrl}${pathName}`, {
        method: 'POST',
        headers: {
          'x-app-auth': authHeader,
          'Content-Type': `multipart/form-data; boundary=${boundary}`,
          'Content-Length': body.length.toString(),
        },
        body: body,
      });
    }

    const resultText = await response.text();
    if (response.ok) {
      console.log('✅ Release published successfully!');
      console.log(resultText);
    } else {
      console.error(`❌ Upload failed (${response.status}):`, resultText);
    }
  } catch (err) {
    console.error('❌ Error uploading release:', err);
  }
}

publishRelease();
