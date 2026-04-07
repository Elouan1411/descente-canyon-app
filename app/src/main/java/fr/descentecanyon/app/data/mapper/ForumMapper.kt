package fr.descentecanyon.app.data.mapper

import fr.descentecanyon.app.data.remote.dto.ScrapedForumActiveTopic
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
