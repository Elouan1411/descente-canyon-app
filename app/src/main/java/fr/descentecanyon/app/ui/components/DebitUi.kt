package fr.descentecanyon.app.ui.components

import androidx.compose.ui.graphics.Color
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.ui.theme.DebitCorrect
import fr.descentecanyon.app.ui.theme.DebitCrue
import fr.descentecanyon.app.ui.theme.DebitFilet
import fr.descentecanyon.app.ui.theme.DebitGros
import fr.descentecanyon.app.ui.theme.DebitInconnu
import fr.descentecanyon.app.ui.theme.DebitSec
import fr.descentecanyon.app.ui.theme.DebitTresGros

fun debitLevelColor(niveau: NiveauDebit): Color = when (niveau) {
    NiveauDebit.SEC -> DebitSec
    NiveauDebit.FILET -> DebitFilet
    NiveauDebit.CORRECT -> DebitCorrect
    NiveauDebit.GROS -> DebitGros
    NiveauDebit.TRES_GROS -> DebitTresGros
    NiveauDebit.CRUE -> DebitCrue
    NiveauDebit.INCONNU -> DebitInconnu
}
