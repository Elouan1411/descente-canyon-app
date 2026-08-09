import { sql } from '@vercel/postgres';

export interface CanyonPdfRecord {
  id: string;
  canyonId: number;
  fileName: string;
  fileSize: number;
  blobUrl: string;
  uploadedAt: number;
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
      CREATE INDEX IF NOT EXISTS idx_canyon_id ON canyon_pdfs(canyon_id);
    `;
  } catch (e) {
    console.error('Error initializing Postgres DB:', e);
  }
}

export async function getPdfsForCanyon(canyonId: number): Promise<CanyonPdfRecord[]> {
  try {
    const { rows } = await sql`
      SELECT id, canyon_id, file_name, file_size, blob_url, uploaded_at 
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
    }));
  } catch (err) {
    await initDb();
    return [];
  }
}

export async function insertPdfRecord(pdf: CanyonPdfRecord): Promise<void> {
  await initDb();
  await sql`
    INSERT INTO canyon_pdfs (id, canyon_id, file_name, file_size, blob_url, uploaded_at)
    VALUES (${pdf.id}, ${pdf.canyonId}, ${pdf.fileName}, ${pdf.fileSize}, ${pdf.blobUrl}, ${pdf.uploadedAt})
  `;
}

export async function deletePdfRecord(pdfId: string): Promise<CanyonPdfRecord | null> {
  try {
    const { rows } = await sql`
      DELETE FROM canyon_pdfs 
      WHERE id = ${pdfId}
      RETURNING id, canyon_id, file_name, file_size, blob_url, uploaded_at
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
    };
  } catch (err) {
    return null;
  }
}
