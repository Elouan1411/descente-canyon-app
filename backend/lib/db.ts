import { sql } from '@vercel/postgres';

export interface CanyonPdfRecord {
  id: string;
  canyonId: number;
  fileName: string;
  fileSize: number;
  blobUrl: string;
  uploadedAt: number;
  mimeType?: string;
  uploaderId?: string;
}

export interface AppReleaseRecord {
  id: string;
  versionCode: number;
  versionName: string;
  releaseNotes: string;
  blobUrl: string;
  uploadedAt: number;
  minSupportedVersionCode: number;
}

export async function initDb() {
  try {
    await sql`
      CREATE TABLE IF NOT EXISTS canyon_pdfs (
        id VARCHAR(255) PRIMARY KEY,
        canyon_id INT NOT NULL,
        file_name VARCHAR(255) NOT NULL,
        file_size BIGINT NOT NULL,
        blob_url TEXT NOT NULL,
        uploaded_at BIGINT NOT NULL
      );
    `;
    await sql`
      ALTER TABLE canyon_pdfs ADD COLUMN IF NOT EXISTS mime_type VARCHAR(100) DEFAULT 'application/pdf';
    `;
    await sql`
      ALTER TABLE canyon_pdfs ADD COLUMN IF NOT EXISTS uploader_id VARCHAR(255) DEFAULT NULL;
    `;
    await sql`
      CREATE INDEX IF NOT EXISTS idx_canyon_id ON canyon_pdfs(canyon_id);
    `;

    await sql`
      CREATE TABLE IF NOT EXISTS app_releases (
        id VARCHAR(255) PRIMARY KEY,
        version_code INT NOT NULL,
        version_name VARCHAR(100) NOT NULL,
        release_notes TEXT NOT NULL,
        blob_url TEXT NOT NULL,
        uploaded_at BIGINT NOT NULL,
        min_supported_version_code INT NOT NULL DEFAULT 1
      );
    `;
    await sql`
      CREATE INDEX IF NOT EXISTS idx_version_code ON app_releases(version_code);
    `;
  } catch (e) {
    console.error('Error initializing Postgres DB:', e);
  }
}

export async function getPdfsForCanyon(canyonId: number): Promise<CanyonPdfRecord[]> {
  try {
    const { rows } = await sql`
      SELECT id, canyon_id, file_name, file_size, blob_url, uploaded_at, mime_type, uploader_id 
      FROM canyon_pdfs 
      WHERE canyon_id = ${canyonId}
      ORDER BY uploaded_at DESC
    `;
    return rows.map((r) => ({
      id: r.id,
      canyonId: Number(r.canyon_id),
      fileName: r.file_name,
      fileSize: Number(r.file_size),
      blobUrl: r.blob_url,
      uploadedAt: Number(r.uploaded_at),
      mimeType: r.mime_type || 'application/pdf',
      uploaderId: r.uploader_id || undefined,
    }));
  } catch (err) {
    await initDb();
    return [];
  }
}

export async function insertPdfRecord(pdf: CanyonPdfRecord): Promise<void> {
  await initDb();
  const mimeType = pdf.mimeType || 'application/pdf';
  const uploaderId = pdf.uploaderId || null;
  await sql`
    INSERT INTO canyon_pdfs (id, canyon_id, file_name, file_size, blob_url, uploaded_at, mime_type, uploader_id)
    VALUES (${pdf.id}, ${pdf.canyonId}, ${pdf.fileName}, ${pdf.fileSize}, ${pdf.blobUrl}, ${pdf.uploadedAt}, ${mimeType}, ${uploaderId})
  `;
}

export async function deletePdfRecord(pdfId: string): Promise<CanyonPdfRecord | null> {
  try {
    const { rows } = await sql`
      DELETE FROM canyon_pdfs 
      WHERE id = ${pdfId}
      RETURNING id, canyon_id, file_name, file_size, blob_url, uploaded_at, mime_type
    `;
    if (rows.length === 0) return null;
    const r = rows[0];
    return {
      id: r.id,
      canyonId: Number(r.canyon_id),
      fileName: r.file_name,
      fileSize: Number(r.file_size),
      blobUrl: r.blob_url,
      uploadedAt: Number(r.uploaded_at),
      mimeType: r.mime_type || 'application/pdf',
    };
  } catch (err) {
    return null;
  }
}

export async function getLatestRelease(): Promise<AppReleaseRecord | null> {
  try {
    const { rows } = await sql`
      SELECT id, version_code, version_name, release_notes, blob_url, uploaded_at, min_supported_version_code
      FROM app_releases
      ORDER BY version_code DESC
      LIMIT 1
    `;
    if (rows.length === 0) return null;
    const r = rows[0];
    return {
      id: r.id,
      versionCode: Number(r.version_code),
      versionName: r.version_name,
      releaseNotes: r.release_notes,
      blobUrl: r.blob_url,
      uploadedAt: Number(r.uploaded_at),
      minSupportedVersionCode: Number(r.min_supported_version_code),
    };
  } catch (err) {
    await initDb();
    return null;
  }
}

export async function insertReleaseRecord(release: AppReleaseRecord): Promise<void> {
  await initDb();
  await sql`
    INSERT INTO app_releases (id, version_code, version_name, release_notes, blob_url, uploaded_at, min_supported_version_code)
    VALUES (${release.id}, ${release.versionCode}, ${release.versionName}, ${release.releaseNotes}, ${release.blobUrl}, ${release.uploadedAt}, ${release.minSupportedVersionCode})
  `;
}

export async function deleteOldReleasesExcept(keepReleaseId: string): Promise<AppReleaseRecord[]> {
  try {
    const { rows } = await sql`
      DELETE FROM app_releases
      WHERE id != ${keepReleaseId}
      RETURNING id, version_code, version_name, release_notes, blob_url, uploaded_at, min_supported_version_code
    `;
    return rows.map((r) => ({
      id: r.id,
      versionCode: Number(r.version_code),
      versionName: r.version_name,
      releaseNotes: r.release_notes,
      blobUrl: r.blob_url,
      uploadedAt: Number(r.uploaded_at),
      minSupportedVersionCode: Number(r.min_supported_version_code),
    }));
  } catch (err) {
    return [];
  }
}
