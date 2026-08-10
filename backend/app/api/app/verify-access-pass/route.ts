import { NextRequest, NextResponse } from 'next/server';
import { verifyHmacAuth } from '@/lib/security';

export async function POST(req: NextRequest) {
  if (!verifyHmacAuth(req)) {
    return NextResponse.json({ error: 'Unauthorized APK signature' }, { status: 403 });
  }

  try {
    const body = await req.json();
    const password = body?.password?.trim();

    if (!password) {
      return NextResponse.json(
        { success: false, error: 'Mot de passe requis' },
        { status: 400 }
      );
    }

    const envPassword = process.env.APP_ACCESS_PASSWORD;

    // Si aucune variable d'environnement n'est définie, on accepte par défaut "CANYON2026"
    const validPasswords = envPassword
      ? envPassword.split(',').map((p) => p.trim())
      : ['CANYON2026'];

    if (validPasswords.includes(password)) {
      return NextResponse.json({ success: true, message: 'Accès autorisé' });
    } else {
      return NextResponse.json(
        { success: false, error: 'Mot de passe d\'accès incorrect' },
        { status: 401 }
      );
    }
  } catch (error) {
    return NextResponse.json(
      { success: false, error: 'Erreur lors de la vérification' },
      { status: 500 }
    );
  }
}
