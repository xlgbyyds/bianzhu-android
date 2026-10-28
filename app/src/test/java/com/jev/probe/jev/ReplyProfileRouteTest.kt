package com.jev.probe.jev

import com.jev.probe.core.Prefs
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg
import com.jev.probe.core.kb.ChatContext
import com.jev.probe.core.kb.Contact
import com.jev.probe.core.kb.ProfileClaim
import com.jev.probe.core.kb.RelationshipProfile
import com.jev.probe.core.kb.LogEntry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReplyProfileRouteTest {
    @Test fun ownCallbackIsNotRelabeledAsHerProfileOrACommitment() {
        val prefs = Prefs(RuntimeEnvironment.getApplication(), "opener-speaker-provenance")
        val profile = RelationshipProfile(
            ProfileClaim("刚认识"), ProfileClaim("意愿未知"),
            listOf(ProfileClaim("用户表示以后想听对方吐槽带教", listOf("db:2"))), emptyList(),
            pacingAdvice = "自然接话", unknowns = listOf("尚未约定"), sourceApp = "com.tencent.mm",
            sourceLineCount = 2, sourcePageCount = 1, sourceMeCount = 1, sourceOtherCount = 1,
            sourceUnknownSideCount = 0, sourceVisibleTimeCount = 0, sourceHistoricalCount = 0,
            sourceFirstObservedAt = 1, sourceLatestObservedAt = 1)
        val snapshot = ChatSnapshot("测试", listOf(
            Msg("other", "明年实习"), Msg("me", "到时候听你吐槽带教哈哈哈")))
        val context = ChatContext(Contact("speaker-test", "测试"),
            listOf(LogEntry("me", "以后听你说", 1, "com.tencent.mm")), emptyList(), profile)
        var calls = 0
        val client = ReplyClient(prefs) { _, _, body ->
            calls++
            val system = body.getJSONArray("messages").getJSONObject(0).getString("content")
            val user = body.getJSONArray("messages").getJSONObject(1).getString("content")
            assertTrue(user.contains("[from=other] 对方：明年实习"))
            assertTrue(user.contains("[from=me] 我：到时候听你吐槽带教哈哈哈"))
            assertTrue(user.lineSequence().any { it.startsWith("[from=me] 我：") && it.contains("以后听你说") })
            assertTrue(user.contains("用户表示以后想听对方吐槽带教"))
            val cardLine = user.lineSequence().first { it.startsWith("另行提供的对方资料卡文字") }
            assertFalse(cardLine.contains("带教"))
            assertTrue(cardLine.endsWith(if (calls == 1) "无" else "她自述参加合唱团"))
            assertTrue(system.contains("没有明确约定"))
            assertTrue(system.contains("不能改写成她的经历"))
            response("""["哈喽 过来跟你聊两句","今天想聊点别的","你平时听什么歌"]""")
        }
        client.openers(snapshot, "刚认识", context)
        client.openers(snapshot, "刚认识", context, "她自述参加合唱团")
        assertEquals(2, calls)
    }

    @Test fun naturalVoiceAppliesToBothEntrypointsWithoutAnExtraRewriteRequest() {
        val prefs = Prefs(RuntimeEnvironment.getApplication(), "natural-voice-contract")
        var calls = 0
        val client = ReplyClient(prefs) { _, _, body ->
            calls++
            val system = body.getJSONArray("messages").getJSONObject(0).getString("content")
            assertTrue(system.contains(ReplyPolicy.NATURAL_VOICE))
            assertTrue(system.contains(ReplyPolicy.LISTENING_AND_CONTINUITY))
            assertTrue(system.contains("没有合适"))
            assertTrue(system.contains("三条是互斥备选"))
            assertTrue(system.contains("不先总结她的情绪"))
            if (calls == 1) {
                assertTrue(system.contains("follow_detail"))
                response("""{"replies":[{"style":"natural","text":"这家份量还挺实在"},{"style":"follow_detail","text":"你这份辣椒是不是放多了"},{"style":"playful","text":"看着都替你觉得辣"}]}""")
            } else {
                assertTrue(system.contains("只有哈喽或表情时"))
                response("""{"replies":[{"style":"fresh_opening","text":"哈喽 过来跟你聊两句"},{"style":"light_callback","text":"那只猫后面还有照片吗"},{"style":"playful","text":"才夸你一句 就得意上了"}]}""")
            }
        }
        val draft = client.draft(ChatSnapshot("测试", listOf(Msg("other", "这碗面辣椒给太多了"))), "刚认识")
        assertEquals(listOf("natural", "follow_detail", "playful"), draft.map { it.style })
        assertEquals(1, calls)
        val openers = client.openers(ChatSnapshot("测试", listOf(Msg("other", "哈喽"))), "刚认识")
        assertEquals(3, openers.size)
        assertEquals(2, calls)
    }

    @Test fun rankingPrefersConcreteNaturalLanguageWithoutDemandingHumor() {
        val question = JevQuestions.rankQuestion(listOf("a", "b", "c"))
            .getJSONObject("best_reply")
        val instruction = question.getString("instructions")
        assertTrue(instruction.contains("A brief plain reply can be best"))
        assertTrue(instruction.contains("forced metaphor"))
        assertTrue(instruction.contains("Avoid copying the user's previous lines"))
        assertEquals("a", question.getJSONObject("criteria").getString("reply_a"))
    }

    @Test fun truncatedProfileRetriesWithLargerBudgetAndReturnsCompleteJson() {
        val prefs = Prefs(RuntimeEnvironment.getApplication(), "profile-length-recovery")
        val budgets = mutableListOf<Int>()
        val client = ReplyClient(prefs) { _, _, body ->
            budgets += body.getInt("max_tokens")
            if (budgets.size == 1) response("""{"facts":[""", "length")
            else response("""{"facts":[]}""")
        }
        assertEquals("""{"facts":[]}""", client.completeProfileRoundTrip("JSON", "records", 0.1))
        assertEquals(listOf(4096, 8192), budgets)
    }

    @Test fun repeatedTruncationHasBoundedRetryAndDoesNotRetryAuthenticationFailure() {
        val prefs = Prefs(RuntimeEnvironment.getApplication(), "profile-length-failure")
        var calls = 0
        val client = ReplyClient(prefs) { _, _, _ ->
            calls++
            response("", "length")
        }
        val error = runCatching { client.completeProfileRoundTrip("JSON", "records", 0.1) }.exceptionOrNull()
        assertEquals(2, calls)
        assertTrue(error is ProfileOutputException)
        assertFalse((error as ProfileOutputException).retryable)
        calls = 0
        val denied = ReplyClient(prefs) { _, _, _ ->
            calls++
            throw ApiException(Route.REPLY, 401, "test")
        }
        assertTrue(runCatching { denied.completeProfileRoundTrip("JSON", "records", 0.1) }.isFailure)
        assertEquals(1, calls)
    }

    @Test fun openRouterDeepSeekUsesItsOwnThinkingControlAndKeepsSelectedModel() {
        val prefs = Prefs(RuntimeEnvironment.getApplication(), "or-profile-route-test")
        prefs.replyBaseUrl = Prefs.DEFAULT_REPLY_BASE
        prefs.replyModel = "deepseek/deepseek-v4.1-flash"
        var captured = JSONObject()
        val client = ReplyClient(prefs) { _, _, body ->
            captured = body
            response("""{"inviter":"me","responder":"other","meetingConfirmed":false}""")
        }
        client.probeProfile()
        assertEquals(prefs.replyModel, captured.getString("model"))
        assertFalse(captured.getJSONObject("reasoning").getBoolean("enabled"))
        assertFalse(captured.has("thinking"))
        assertEquals("json_object", captured.getJSONObject("response_format").getString("type"))
        prefs.replyModel = "deepseek/deepseek-r1-0528"
        ReplyClient(prefs) { _, _, body ->
            captured = body
            response("""{"inviter":"me","responder":"other","meetingConfirmed":false}""")
        }.probeProfile()
        assertFalse(captured.has("reasoning"))
        prefs.replyModel = "deepseek/deepseek-v4.1-flash"
        prefs.replyBaseUrl = "https://example.com/v1"
        ReplyClient(prefs) { _, _, body ->
            captured = body
            response("""{"inviter":"me","responder":"other","meetingConfirmed":false}""")
        }.probeProfile()
        assertFalse(captured.has("reasoning"))
    }

    @Test fun runningProfileKeepsItsOriginalRouteWhenSettingsChange() {
        val prefs = Prefs(RuntimeEnvironment.getApplication(), "profile-frozen-route")
        prefs.replyBaseUrl = Prefs.DEEPSEEK_BASE
        prefs.replyModel = "deepseek-flash"
        val client = ReplyClient(prefs) { url, _, body ->
            assertTrue(url.startsWith(Prefs.DEEPSEEK_BASE))
            assertEquals("deepseek-flash", body.getString("model"))
            response("""{"inviter":"me","responder":"other","meetingConfirmed":false}""")
        }
        val route = client.profileCacheRoute
        prefs.replyBaseUrl = Prefs.DEFAULT_REPLY_BASE
        prefs.replyModel = "other-model"
        client.probeProfile()
        assertEquals(route, client.profileCacheRoute)
    }

    @Test fun profileProbeRejectsWrongSpeakerAndStringBoolean() {
        val prefs = Prefs(RuntimeEnvironment.getApplication(), "profile-probe-validation")
        val wrong = ReplyClient(prefs) { _, _, _ ->
            response("""{"inviter":"other","responder":"me","meetingConfirmed":false}""")
        }
        assertTrue(runCatching { wrong.probeProfile() }.isFailure)
        val wrongType = ReplyClient(prefs) { _, _, _ ->
            response("""{"inviter":"me","responder":"other","meetingConfirmed":"false"}""")
        }
        assertTrue(runCatching { wrongType.probeProfile() }.isFailure)
    }

    private fun response(content: String, finish: String = "stop") = JSONObject().put("choices",
        JSONArray().put(JSONObject().put("finish_reason", finish)
            .put("message", JSONObject().put("content", content))))

    @Test fun openersUseFeedbackAndContactContextInOneReplyRequestWithStyleMetadata() {
        val prefs = Prefs(RuntimeEnvironment.getApplication(), "opener-feedback-test")
        prefs.clearReplyFeedback()
        prefs.replyKey = "test-reply-key"
        prefs.rememberReplyFeedback("style:playful")
        prefs.rememberReplyFeedback("less_ai")
        prefs.rememberReplyFeedback("less_questions")
        prefs.rememberReplyFeedback("less_forward")
        var calls = 0
        val snapshot = ChatSnapshot("测试联系人", listOf(Msg("me", "之前聊到的小猫", visibleTime = "9月15日 16:00")))
        val context = ChatContext(Contact("test", "测试联系人", relationship = "熟悉亲密", notes = "喜欢猫"),
            emptyList(), emptyList())
        val client = ReplyClient(prefs) { url, key, body ->
            calls++
            assertEquals(prefs.replyEndpoint(), url)
            assertEquals("test-reply-key", key)
            assertEquals("json_object", body.getJSONObject("response_format").getString("type"))
            val messages = body.getJSONArray("messages")
            val system = messages.getJSONObject(0).getString("content")
            assertTrue(system.contains("开场助手"))
            assertTrue(system.contains(prefs.replyFeedbackGuidance()))
            val user = messages.getJSONObject(1).getString("content")
            assertTrue(user.contains("熟悉亲密"))
            assertTrue(user.contains("喜欢猫"))
            assertTrue(user.contains("我："))
            assertTrue(user.contains("9月15日 16:00"))
            response("""{"replies":[{"style":"fresh_opening","text":"今天换我讲点我的事。"},{"style":"light_callback","text":"你的猫猫队友上线了！"},{"style":"playful","text":"今天想多黏你一会。"}]}""")
        }
        val suggestions = client.openers(snapshot, "熟悉亲密", context)
        assertEquals(1, calls)
        assertEquals(listOf("fresh_opening", "light_callback", "playful"), suggestions.map { it.style })
        assertEquals(listOf("今天换我讲点我的事", "你的猫猫队友上线了", "今天想多黏你一会"), suggestions.map { it.text })
        assertTrue(suggestions.all { it.reason.isEmpty() })
    }

    @Test fun timeAndRealVoiceSamplesReachDraftAndOpenerWithoutAnExtraModelRequest() {
        val prefs = Prefs(RuntimeEnvironment.getApplication(), "voice-payload-test")
        val ctx = ChatContext(null, emptyList(), emptyList(), replyVoiceGuidance = "明确认可：好家伙 你比猫还能折腾")
        var calls = 0
        val client = ReplyClient(prefs) { _, _, body ->
            calls++
            val system = body.getJSONArray("messages").getJSONObject(0).getString("content")
            val user = body.getJSONArray("messages").getJSONObject(1).getString("content")
            assertTrue(user.contains("设备当前本地时间："))
            assertTrue(user.contains("好家伙 你比猫还能折腾"))
            assertTrue(system.contains("不写旁白"))
            response("""["好家伙 比猫还能折腾","这小家伙又闯祸了","猫都快成你室友了"]""")
        }
        val snapshot = ChatSnapshot("测试", listOf(Msg("other", "猫又把杯子推倒了")))
        client.draft(snapshot, "刚认识", ctx); client.openers(snapshot, "刚认识", ctx)
        assertEquals(2, calls)
        val state = JevQuestions.buildState(snapshot, "刚认识")
        val last = state.getJSONObject("chat").getJSONArray("messages").getJSONObject(0).getString("text")
        assertTrue(last.contains("设备当前本地时间："))
    }

    @Test fun legacyOpenerArraysKeepFeedbackStylesAndExistingContentFilters() {
        val prefs = Prefs(RuntimeEnvironment.getApplication(), "opener-legacy-test")
        val client = ReplyClient(prefs) { _, _, _ ->
            response("""["今天换我讲点我的事。","要不要出来约饭","今天想多黏你一会。"]""")
        }
        val suggestions = client.openers(ChatSnapshot("测试联系人", emptyList()), "熟悉亲密")
        assertEquals(listOf("今天换我讲点我的事", "今天想多黏你一会"), suggestions.map { it.text })
        assertEquals(listOf("fresh_opening", "playful"), suggestions.map { it.style })
    }

    @Test fun profileAndShortRepliesDisableDeepSeekThinkingWithoutChangingOtherProviders() {
        val prefs = Prefs(RuntimeEnvironment.getApplication(), "profile-route-test")
        prefs.replyBaseUrl = Prefs.DEEPSEEK_BASE
        prefs.replyModel = "deepseek-v4-flash"
        prefs.replyKey = "test-reply-key"
        prefs.judgeKey = "test-judge-key"
        var captured = JSONObject()
        val client = ReplyClient(prefs) { url, key, body ->
            assertEquals(prefs.replyEndpoint(), url)
            assertEquals("test-reply-key", key)
            captured = body
            response("{}")
        }
        assertEquals("{}", client.completeProfileRoundTrip("JSON", "JSON", 0.1))
        assertEquals("deepseek-v4-flash", captured.getString("model"))
        assertEquals(4096, captured.getInt("max_tokens"))
        assertEquals("disabled", captured.getJSONObject("thinking").getString("type"))
        assertEquals("json_object", captured.getJSONObject("response_format").getString("type"))

        client.ping()
        assertEquals("disabled", captured.getJSONObject("thinking").getString("type"))
        prefs.replyBaseUrl = "https://example.com/v1"
        ReplyClient(prefs) { url, _, body ->
            assertEquals(prefs.replyEndpoint(), url)
            captured = body
            response("{}")
        }.completeProfileRoundTrip("JSON", "JSON", 0.1)
        assertFalse(captured.has("thinking"))

        val truncated = ReplyClient(prefs) { _, _, _ -> response("", "length") }
        assertTrue(runCatching { truncated.completeProfileRoundTrip("JSON", "JSON", 0.1) }
            .exceptionOrNull() is ProfileOutputException)
        val empty = ReplyClient(prefs) { _, _, _ -> response(" ") }
        assertTrue(runCatching { empty.completeProfileRoundTrip("JSON", "JSON", 0.1) }
            .exceptionOrNull() is ProfileOutputException)
    }
}
