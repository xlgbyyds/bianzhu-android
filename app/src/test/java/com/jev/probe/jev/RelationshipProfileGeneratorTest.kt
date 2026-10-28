package com.jev.probe.jev

import com.jev.probe.core.kb.ProfileLine
import com.jev.probe.core.kb.RelationshipSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentLinkedQueue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RelationshipProfileGeneratorTest {
    @Test fun exhaustedOutputBudgetDoesNotTriggerAnotherFormatRetry() {
        var calls = 0
        val source = RelationshipSource("com.tencent.mm", 1, listOf(line(1)), 1L, 1L)
        val error = runCatching {
            RelationshipProfileGenerator({ _, _, _ ->
                calls++
                throw ProfileOutputException("budget exhausted", truncated = true, retryable = false)
            }).generate(source)
        }.exceptionOrNull()
        assertTrue(error is ProfileOutputException)
        assertEquals(1, calls)
    }

    private fun line(id: Int) = ProfileLine(
        ref = "db:$id", side = if (id % 2 == 0) "other" else "me", text = "这是第${id}条测试消息",
        observedAt = id.toLong(), app = "com.tencent.mm", kind = "TEXT", visibleTime = null,
        timeConfidence = "unknown", historical = true
    )

    @Test fun emptyOutputRetriesOnlyTheFailedChunkOnce() {
        var calls = 0
        val source = RelationshipSource("com.tencent.mm", 1, listOf(line(1)), 1L, 1L)
        RelationshipProfileGenerator({ _, user, _ ->
            calls++
            when (calls) {
                1 -> ""
                2 -> { assertTrue(user.contains("格式重试")); "{}" }
                else -> "{}"
            }
        }).generate(source)
        assertEquals(3, calls) // Initial chunk, its retry, then final synthesis.
    }

    @Test fun badOutputHasBoundedRetriesAndApiErrorsAreNotRetried() {
        val source = RelationshipSource("com.tencent.mm", 1, listOf(line(1)), 1L, 1L)
        var invalidCalls = 0
        val invalid = runCatching {
            RelationshipProfileGenerator({ _, _, _ -> invalidCalls++; "" }).generate(source)
        }.exceptionOrNull()
        assertTrue(invalid is ProfileOutputException)
        assertEquals(2, invalidCalls)
        var apiCalls = 0
        val denied = runCatching {
            RelationshipProfileGenerator({ _, _, _ ->
                apiCalls++
                throw ApiException(Route.REPLY, 401, "test authentication failure")
            }).generate(source)
        }.exceptionOrNull()
        assertTrue(denied is ApiException)
        assertEquals(1, apiCalls)
    }

    @Test fun chunksLongHistoryAndCitesOnlyStoredEvidence() {
        val prompts = ConcurrentLinkedQueue<String>()
        val source = RelationshipSource("com.tencent.mm", 83, (1..351).map(::line), 1L, 351L)
        val client = RelationshipProfileGenerator({ _, user, _ ->
            prompts.add(user)
            if (user.startsWith("任务：将下方分段证据摘要")) {
                """{"relationshipStage":{"text":"持续交流但关系未确认","sourceRefs":["db:1"],"confidence":0.65},
                    "attitudeAssessment":{"text":"愿意延续部分话题，恋爱意愿仍不能确认","sourceRefs":["db:2"],"confidence":0.58},
                    "conversationDirection":{"text":"可以从双方都参与过的轻松兴趣话题继续","sourceRefs":["db:1","db:2"],"confidence":0.72},
                    "knownFacts":[{"text":"双方持续交流","sourceRefs":["db:1","db:2"],"confidence":0.8}],
                    "interactionPatterns":[],"pacingAdvice":"保持双向轻松交流","unknowns":["是否愿意正式恋爱尚未直接确认"]}"""
            } else {
                """{"facts":[{"text":"有持续交流","sourceRefs":["db:1"],"confidence":0.8}],"patterns":[],"attitude":null,"themes":[]}"""
            }
        })
        val progress = ArrayList<ProfileGenerationProgress>()
        val result = client.generate(source) { progress.add(it) }
        val chunks = prompts.count { it.startsWith("任务：只分析这一位联系人") }
        assertEquals(3, chunks)
        assertTrue(prompts.any { it.contains("[db:351]") })
        assertTrue(prompts.any { it.contains("单方主动、暧昧/性话题") })
        assertTrue(prompts.any { it.contains("采集时间和采集顺序都不代表发送时间") })
        assertEquals(351, result.sourceLineCount)
        assertEquals(listOf("db:1", "db:2"), result.knownFacts.single().sourceRefs)
        assertEquals(listOf("db:1", "db:2"), result.conversationDirection.sourceRefs)
        assertTrue(prompts.any { it.contains("conversationDirection:{text,sourceRefs,confidence}") })
        assertTrue(result.contextText().contains("非对方确认"))
        assertTrue(progress.any { it.phase.contains("$chunks/$chunks") })
        assertEquals(95, progress.last().percent)
    }

    @Test fun userOnlyEvidenceCannotSupportMutualRelationshipOrOtherPersonsAttitude() {
        val source = RelationshipSource("com.tencent.mm", 1, listOf(line(1)), 1L, 1L)
        val result = RelationshipProfileGenerator({ _, user, _ ->
            if (user.startsWith("任务：将下方分段证据摘要"))
                """{"relationshipStage":{"text":"双方已确认恋爱关系","sourceRefs":["db:1"],"confidence":0.99},
                    "attitudeAssessment":{"text":"她明确想和用户恋爱","sourceRefs":["db:1"],"confidence":0.99},
                    "knownFacts":[],"interactionPatterns":[],"pacingAdvice":"立即确认关系","unknowns":[]}"""
            else """{"facts":[],"patterns":[],"attitude":null,"themes":[]}"""
        }).generate(source)

        assertTrue(result.relationshipStage.sourceRefs.isEmpty())
        assertTrue(result.relationshipStage.text.contains("不能据此认定"))
        assertTrue(result.attitudeAssessment.sourceRefs.isEmpty())
        assertTrue(result.attitudeAssessment.text.contains("不能确定她是否希望"))
        assertTrue(result.conversationDirection.sourceRefs.isEmpty())
    }

    @Test fun largeHistoriesUseBoundedEvidencePreservingMergeRounds() {
        val prompts = ConcurrentLinkedQueue<String>()
        val source = RelationshipSource("com.tencent.mm", 240, (1..1401).map(::line), 1L, 1401L)
        val progress = ConcurrentLinkedQueue<ProfileGenerationProgress>()
        RelationshipProfileGenerator({ _, user, _ ->
            prompts.add(user)
            when {
                user.startsWith("任务：将下方分段证据摘要") ->
                    """{"relationshipStage":{"text":"记录不足以确认关系","sourceRefs":[],"confidence":0.2},
                       "attitudeAssessment":{"text":"记录不足以判断","sourceRefs":[],"confidence":0.2},
                       "conversationDirection":{"text":"优先回应当前话题","sourceRefs":[],"confidence":0.2},
                       "knownFacts":[],"interactionPatterns":[],"pacingAdvice":"自然交流","unknowns":["关系意愿未知"]}"""
                user.startsWith("任务：跨分段合并") ->
                    """{"facts":[],"patterns":[],"attitude":null,"themes":[]}"""
                else -> """{"facts":[],"patterns":[],"attitude":null,"themes":[]}"""
            }
        }).generate(source) { progress.add(it) }

        val chunkCalls = prompts.count { it.startsWith("任务：只分析这一位联系人") }
        val mergeCalls = prompts.count { it.startsWith("任务：跨分段合并") }
        assertTrue(chunkCalls > 5)
        assertTrue(prompts.any { it.startsWith("任务：只分析这一位联系人") && it.contains("[db:1]") })
        assertTrue(prompts.any { it.startsWith("任务：只分析这一位联系人") && it.contains("[db:1401]") })
        assertTrue(mergeCalls > 0)
        assertEquals(chunkCalls + mergeCalls + 1, prompts.size)
        assertTrue(progress.any { it.phase.contains("已合并第 1 轮摘要") })
        assertEquals(95, progress.last().percent)
    }

    @Test fun unsupportedEvidenceCannotBecomeAConfidentMindReadingClaim() {
        val source = RelationshipSource("com.tencent.mm", 1, listOf(line(1)), 1L, 1L)
        val client = RelationshipProfileGenerator({ _, user, _ ->
            if (user.startsWith("任务：将下方分段证据摘要"))
                """{"relationshipStage":{"text":"她肯定想当女朋友","sourceRefs":["db:999"],"confidence":1.0},
                    "attitudeAssessment":{"text":"她已愛上我","sourceRefs":["db:999"],"confidence":1.0},
                    "knownFacts":[],"interactionPatterns":[],"pacingAdvice":"直接确定关系","unknowns":[]}"""
            else """{"facts":[],"patterns":[],"attitude":null,"themes":[]}"""
        })
        val result = client.generate(source)
        assertTrue(result.relationshipStage.sourceRefs.isEmpty())
        assertTrue(result.relationshipStage.confidence <= 0.25)
        assertTrue(result.attitudeAssessment.sourceRefs.isEmpty())
        assertTrue(result.attitudeAssessment.confidence <= 0.2)
        assertFalse(result.attitudeAssessment.text.contains("她已愛上我"))
    }
}
