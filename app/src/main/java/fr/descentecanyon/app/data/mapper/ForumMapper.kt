package fr.descentecanyon.app.data.mapper

import fr.descentecanyon.app.data.remote.dto.ScrapedForumActiveTopic
import fr.descentecanyon.app.data.remote.dto.ScrapedForumCategory
import fr.descentecanyon.app.domain.model.ForumCategory
import fr.descentecanyon.app.domain.model.ForumActiveTopic

fun ScrapedForumActiveTopic.toDomain(): ForumActiveTopic = ForumActiveTopic(
    topicId = topicId,
    title = title,
    forumId = forumId,
    forumName = forumName,
    replyCount = replyCount,
    viewCount = viewCount,
    lastAuthor = lastAuthor,
    lastPostedAtText = lastPostedAtText,
    lastPostedAtEpochMs = lastPostedAtEpochMs,
    topicUrl = topicUrl,
    lastMessageUrl = lastMessageUrl,
)

fun ScrapedForumCategory.toDomain(): ForumCategory = ForumCategory(
    forumId = forumId,
    forumName = forumName,
    forumUrl = forumUrl,
)
