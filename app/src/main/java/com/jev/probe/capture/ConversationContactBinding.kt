package com.jev.probe.capture

/** A manual choice is a request context, not permission to record under a different identity. */
internal class ConversationContactBinding {
    private var choice: Pair<ConversationSession.Target, String>? = null
    fun select(target: ConversationSession.Target, contactId: String) {
        require(!target.title.isNullOrBlank() && contactId.isNotBlank())
        choice = target to contactId
    }
    fun contactId(target: ConversationSession.Target?): String? = choice?.takeIf { it.first == target }?.second
    fun observe(target: ConversationSession.Target?) { if (choice?.first != target) choice = null }
    fun clear() { choice = null }
}
