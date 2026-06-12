package fr.descentecanyon.app.data.remote.scraper

import fr.descentecanyon.app.data.remote.dto.ScrapedForumActiveTopic
import fr.descentecanyon.app.data.remote.dto.ScrapedForumCategory
import java.net.URI
import java.time.OffsetDateTime
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

internal object ForumParser {

    fun parseCategories(doc: Document): List<ScrapedForumCategory> {
        return doc.select("a.forumtitle, a[href*=viewforum]")
            .mapNotNull { anchor ->
                val forumName = anchor.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val forumUrl = sanitizeForumUrl(anchor.absUrl("href").ifBlank { anchor.attr("href") })
                val forumId = forumUrl.extractQueryInt("f")
                ScrapedForumCategory(
                    forumId = forumId,
                    forumName = forumName,
                    forumUrl = forumUrl,
                )
            }
            .distinctBy { category -> category.forumId ?: category.forumName.lowercase() }
            .sortedBy { it.forumName.lowercase() }
    }

    fun parseActiveTopics(doc: Document): List<ScrapedForumActiveTopic> {
        return doc.select("ul.topiclist.topics > li.row").mapNotNull(::parseTopicRow)
    }

    private fun parseTopicRow(row: Element): ScrapedForumActiveTopic? {
        val titleAnchor = row.selectFirst("a.topictitle") ?: return null
        val topicUrl = sanitizeForumUrl(titleAnchor.absUrl("href").ifBlank { titleAnchor.attr("href") })
        val topicId = topicUrl.extractQueryInt("t") ?: return null

        val lastPost = row.selectFirst("dd.lastpost") ?: return null
        val forumAnchor = lastPost.selectFirst("a[href*=viewforum]")
            ?: row.select("a[href*=viewforum]").lastOrNull()
            ?: return null
        val lastPostLink = lastPost.selectFirst("a[href*=viewtopic][href*=p=]")
            ?: lastPost.select("a[href*=viewtopic]").lastOrNull()
            ?: return null
        val time = lastPost.selectFirst("time")

        return ScrapedForumActiveTopic(
            topicId = topicId,
            title = titleAnchor.text().trim(),
            forumId = forumAnchor.absUrl("href").ifBlank { forumAnchor.attr("href") }.let(::sanitizeForumUrl).extractQueryInt("f"),
            forumName = forumAnchor.text().trim(),
            replyCount = row.selectFirst("dd.posts")?.text()?.extractInt() ?: 0,
            viewCount = row.selectFirst("dd.views")?.text()?.extractInt() ?: 0,
            lastAuthor = lastPost.selectFirst("a.username, a.username-coloured")?.text()?.trim(),
            lastPostedAtText = time?.text()?.trim().orEmpty(),
            lastPostedAtEpochMs = time?.attr("datetime")
                ?.takeIf { it.isNotBlank() }
                ?.let { raw -> runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull() },
            topicUrl = topicUrl,
            lastMessageUrl = sanitizeForumUrl(lastPostLink.absUrl("href").ifBlank { lastPostLink.attr("href") }),
        )
    }
}

internal fun sanitizeForumUrl(url: String): String {
    if (url.isBlank()) return url

    return runCatching {
        val uri = URI(url)
        val filteredQuery = uri.rawQuery
            ?.split('&')
            ?.filterNot { it.startsWith("sid=") }
            ?.joinToString("&")
            ?.ifBlank { null }
        URI(uri.scheme, uri.authority, uri.path, filteredQuery, uri.fragment).toString()
    }.getOrElse { url }
}

private fun String.extractQueryInt(key: String): Int? {
    return Regex("(?:[?&])$key=(\\d+)")
        .find(this)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
}
