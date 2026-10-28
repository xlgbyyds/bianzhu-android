package com.jev.probe.jev

import com.jev.probe.core.*
import org.junit.Assert.*
import org.junit.Test

class ReplyTurnPolicyTest {
    @Test fun explicitFeedbackAndUnresolvedHurtDoNotRequirePlayfulCandidate() {
        val feedback = ChatSnapshot("测试", listOf(Msg("other", "你讲话真像豆包"), Msg("other", "我绷不住了")))
        assertEquals(ReplyTurnPolicy.Mode.STYLE_FEEDBACK, ReplyTurnPolicy.mode(feedback))
        val hurt = ChatSnapshot("测试", listOf(Msg("other", "难过还是会偶尔难过一下吧")))
        assertEquals(ReplyTurnPolicy.Mode.HURT, ReplyTurnPolicy.mode(hurt))
        assertFalse(ReplyTurnPolicy.styles(ReplyTurnPolicy.mode(hurt), listOf("playful")).contains("playful"))
    }
    @Test fun ownTextAndOldFeedbackOutsideTheTailDoNotForceTheTurnMode() {
        val snapshot = ChatSnapshot("测试", listOf(Msg("other", "你讲话像AI"), Msg("other", "哈哈哈"),
            Msg("other", "吃饭啦"), Msg("other", "今天这个好吃"), Msg("me", "我有点难过")))
        assertEquals(ReplyTurnPolicy.Mode.ORDINARY, ReplyTurnPolicy.mode(snapshot))
        assertEquals(ReplyTurnPolicy.Mode.ORDINARY, ReplyTurnPolicy.mode(ChatSnapshot("测试", listOf(Msg("other", "不难过了")))))
    }
}
