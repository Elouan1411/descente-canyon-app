package fr.descentecanyon.app.data.remote.scraper

import fr.descentecanyon.app.domain.model.CanyonInterestRating
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

internal object InterestRatingParser {
    fun parse(doc: Document, canyonId: Int): CanyonInterestRating {
        val text = doc.text()
        return CanyonInterestRating(
            canyonId = canyonId,
            personalRating = personalRating(text),
            averageRating = ratingAfterLabel(text, "Moyenne des notes"),
            medianRating = ratingAfterLabel(text, "Médiane des notes"),
            voteCount = Regex("Répartition des\\s+(\\d+)\\s+notes", RegexOption.IGNORE_CASE)
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull(),
        )
    }

    fun parseForm(doc: Document): InterestRatingForm? {
        val form = doc.selectFirst("form[action*=canyon-interet]")
            ?: doc.selectFirst("form:has(input[name=vote])")
            ?: return null
        if (!form.hasFormControl("vote")) return null

        val submit = form.selectFirst("input[type=submit][name]")
        val submitName = submit?.attr("name")?.takeIf { it.isNotBlank() } ?: return null
        return InterestRatingForm(
            defaults = form.readFormDefaults(),
            submitName = submitName,
            submitValue = submit.attr("value").ifBlank { "enregistrer" },
        )
    }

    private fun personalRating(text: String): Float? {
        return Regex("vous avez signalé un intérêt de\\s*([0-4](?:[,.]\\d)?)\\s*/\\s*4", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.extractFloat()
    }

    private fun ratingAfterLabel(text: String, label: String): Float? {
        return Regex("$label\\s*:\\s*.*?([0-4](?:[,.]\\d)?)\\s*/\\s*4", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.extractFloat()
    }
}

internal data class InterestRatingForm(
    val defaults: Map<String, String>,
    val submitName: String,
    val submitValue: String,
)

private fun Element.readFormDefaults(): Map<String, String> {
    return select("input[name], textarea[name], select[name]")
        .mapNotNull { element ->
            val name = element.attr("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val type = element.attr("type")
            if (type == "button" || type == "submit" || type == "reset") return@mapNotNull null
            val value = when (element.tagName()) {
                "textarea" -> element.text()
                "select" -> element.selectFirst("option[selected]")?.attr("value").orEmpty()
                else -> element.attr("value")
            }
            name to value
        }
        .toMap()
}

private fun Element.hasFormControl(name: String): Boolean {
    return getElementsByAttributeValue("name", name).isNotEmpty()
}
