package com.jev.probe.capture

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.jev.probe.core.*
import com.jev.probe.core.kb.KbStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w610dp-h1356dp-xhdpi")
class QQAdapterTest {
    private val pkg = QQAdapter.PACKAGE
    private val res get() = RuntimeEnvironment.getApplication().resources
    private val width get() = res.displayMetrics.widthPixels
    private fun node(id: String?, text: String?, top: Int, left: Double, right: Double) =
        AccessibilityNodeInfo.obtain().apply {
            packageName = pkg; className = "android.widget.TextView"
            viewIdResourceName = id?.let { "$pkg:id/$it" }; this.text = text
            isVisibleToUser = true
            setBoundsInScreen(Rect((left*width).toInt(),top,(right*width).toInt(),top+80))
        }
    private fun page(vararg children: AccessibilityNodeInfo, input: Boolean = true): AccessibilityNodeInfo =
        node(null,null,0,0.0,1.0).apply {
            children.forEach { shadowOf(this).addChild(it) }
            if(input) shadowOf(this).addChild(node("input",null,2300,.04,.75))
            seal(this)
        }
    private fun seal(n: AccessibilityNodeInfo) {
        ReflectionHelpers.callInstanceMethod<Void>(n,"setSealed",ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType,true))
    }
    @Test fun bothVerifiedVersionsReadTitleAndLongBubbleSides() {
        for((bubble,title) in listOf("mjn" to "371","mjm" to "3o4")) {
            val s=QQAdapter().extract(page(node(title,"测试联系人",180,.1,.5),
                node(bubble,"一条很长的对方消息",600,.13,.79),
                node(bubble,"一条很长的我的消息",800,.21,.87),
                node("mjq","昵称不当消息",580,.13,.5),node("j64","在线 - 5G",240,.1,.4)),res)!!
            assertEquals("测试联系人",s.title)
            assertEquals(listOf("other","me"),s.messages.map { it.side })
            assertEquals(2,s.messages.size)
        }
    }
    @Test fun noInputOrWrongPackageOrExplicitGroupNeverBecomesPrivateChat() {
        assertNull(QQAdapter().extract(page(node("mjm","预览",500,.13,.4),input=false),res))
        val other=node(null,null,0,0.0,1.0).apply { packageName="com.tencent.mm" }; seal(other)
        assertNull(QQAdapter().extract(other,res))
        val group=node(null,null,180,.8,.9).apply { contentDescription="群聊设置" }
        assertNull(QQAdapter().extract(page(group),res))
    }
    @Test fun visibleTimeIsCarriedForwardWithoutInventingDateForEarlierRows() {
        val s=QQAdapter().extract(page(node("mjm","更早",400,.13,.4),
            node(null,"09/02 上午8:33",550,.39,.61),node("mjm","早上",650,.13,.4),
            node(null,"09/02 上午8:56",900,.39,.61),node("mjm","稍后",1000,.6,.87)),res)!!
        assertNull(s.messages[0].visibleTime)
        assertEquals("unknown",s.messages[0].timeConfidence)
        assertEquals(listOf("09/02 上午8:33","09/02 上午8:56"),s.messages.drop(1).map { it.visibleTime })
        assertTrue(s.messages.drop(1).all { it.timeConfidence=="partial" })
    }
    @Test fun duplicatesNeedSamePositionAndAmbiguousSideStaysUnknown() {
        val s=QQAdapter().extract(page(node("mjm","哈哈",400,.13,.3),node("mjm","哈哈",402,.13,.3),
            node("mjm","哈哈",600,.13,.3),node("mjm","不确定",800,.3,.7)),res)!!
        assertEquals(3,s.messages.size)
        assertEquals("unknown",s.messages.last().side)
    }
    @Test fun slashDateFeedsDelayedReplyAndMemoryDateEvidence() {
        val s=ChatSnapshot("测试联系人",listOf(Msg("other","感谢",visibleTime="09/02 上午8:56",timeConfidence="partial")))
        val timing=ConversationTiming.from(s,java.time.LocalDateTime.of(2026,11,10,20,0))
        assertEquals(69L,timing.ageDays)
        assertTrue(timing.restart)
        assertTrue(com.jev.probe.core.kb.MessageTimeEvidence.conflicts("09/02 上午8:56","09/03 上午8:56"))
        assertFalse(com.jev.probe.core.kb.MessageTimeEvidence.conflicts("09/02 上午8:56","9月2日 上午8:56"))
        assertNull(pageDateAgeDays("8:56",java.time.LocalDate.of(2026,11,10)))
        assertNull(pageDateAgeDays("02/30 上午8:56",java.time.LocalDate.of(2026,11,10)))
    }
    @Test fun hiddenNodesAreNotCollectedAndEmptyChatIsStillRecognized() {
        val hidden=node("mjm","隐藏页",600,.13,.3).apply { isVisibleToUser=false }
        assertTrue(QQAdapter().extract(page(hidden),res)!!.messages.isEmpty())
    }
    @Test fun qqMemoryOptInIsSeparateFromWechatAndUnknownSpeakerCannotBeSaved() {
        assertTrue(pkg in ChatAppCapabilities.suggestions)
        assertTrue(pkg in ChatAppCapabilities.automaticMemory)
        val kb=KbStore.get(RuntimeEnvironment.getApplication())
        kb.saveOrMergeContact("同名测试","com.tencent.mm")
        val wechat=kb.findContact("同名测试","com.tencent.mm")!!
        assertNull(kb.findContact("同名测试",pkg))
        kb.saveOrMergeContact("同名测试",pkg)
        val qq=kb.findContact("同名测试",pkg)!!
        assertNotEquals(wechat.id,qq.id)
        assertTrue(qq.memoryEnabled)
        val result=kb.captureVisibleDetailed(qq.id,ChatSnapshot("同名测试",listOf(Msg("unknown","待核实"))),
            pkg,false,System.currentTimeMillis(),Long.MAX_VALUE)
        assertEquals(0,result.added)
        assertTrue(result.needsReview)
        assertEquals(wechat,kb.findContact("同名测试","com.tencent.mm"))
    }
    // Optional local-only replay of USB evidence. Private XML/output never enters source control.
    @Test fun replayPrivateDeviceTreeWhenProvided() {
        val path=System.getenv("QQ_DEVICE_XML") ?: return
        val doc=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path))
        fun convert(e: Element): AccessibilityNodeInfo = AccessibilityNodeInfo.obtain().apply {
            packageName=e.getAttribute("package"); className=e.getAttribute("class")
            viewIdResourceName=e.getAttribute("resource-id"); text=e.getAttribute("text")
            contentDescription=e.getAttribute("content-desc"); isVisibleToUser=e.getAttribute("visible-to-user")!="false"
            val b=Regex("-?\\d+").findAll(e.getAttribute("bounds")).map { it.value.toInt() }.toList()
            if(b.size==4) setBoundsInScreen(Rect(b[0],b[1],b[2],b[3]))
            for(i in 0 until e.childNodes.length) (e.childNodes.item(i) as? Element)?.let { shadowOf(this).addChild(convert(it)) }
        }
        val root=convert(doc.getElementsByTagName("node").item(0) as Element); seal(root)
        val s=QQAdapter().extract(root,res)!!
        assertTrue(s.messages.isNotEmpty())
        assertTrue(s.messages.all { it.side in setOf("me","other") })
        File(File(path).parentFile,"parsed-private.json").writeText(JSONObject().put("title",s.title)
            .put("messages",JSONArray(s.messages.map { JSONObject().put("side",it.side).put("text",it.text)
                .put("time",it.visibleTime).put("confidence",it.timeConfidence) })).toString(2))
    }
}
