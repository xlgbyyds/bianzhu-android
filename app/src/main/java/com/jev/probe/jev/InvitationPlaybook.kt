package com.jev.probe.jev

/**
 * Adapted from goutoujunshi (MIT, Copyright (c) 2026 powerycy).
 * Pinned upstream: 6db7354a4002dc7c448a9c87ffdad8132570c9d3.
 * Sources: references/practical/主动表达、第一次见面与自然接触.md
 * and references/practical/实战话术编排器：从一句回复到后续分支.md.
 * License distributed in assets/third-party/goutoujunshi-LICENSE.txt.
 * Examples are fictional writing references, NEVER facts about a real contact.
 */
object InvitationPlaybook {
    const val SOURCE = "参考狗头军师场景指南 · MIT · 6db7354"
    const val URL = "https://github.com/shengjidaguai-china/goutoujunshi"

    fun examples(action: InvitationAction): String {
        val cases = when (action) {
            InvitationAction.INVITE -> """
                场景：双方聊到想尝试的食物或活动
                普通版：那找天下午一起去吧 看看是不是真那么好吃
                轻松版：这个话题留点当面聊 周末一起去看看
                场景：还没有确定共同活动 想直接表达见面意愿
                普通版：想约你出来走走 公园或者河边都行
            """
            InvitationAction.REFRAME -> """
                场景：之前时间不合适 现在对方明确说空下来了
                普通版：那周末要不要出来走走 上次没约成
                场景：对方明确担心见面尴尬
                普通版：找个你熟悉的地方坐会儿吧 不用安排一整天
                场景：双方已有接梗基础 用轻松说法带出新方案
                轻松版：这个话题在微信里聊有点浪费 找天出来继续说
            """
            InvitationAction.CONFIRM -> """
                场景：对方明确说周日下午可以
                普通版：那周日下午见 你想喝点东西还是走走
                场景：对方已取消一次 现在主动补约 且双方习惯玩笑
                轻松版：补约账本可以销账了 那就周日下午
                不用这些例子的日期替代真实聊天里已商定的日期
            """
            InvitationAction.CLARIFY -> """
                场景：对方愿意考虑 活动或时间尚不明确
                普通版：你更想找个地方坐坐 还是出来走走
                普通版：你周末哪天比较方便
                只问一个会改变安排的实际条件 不连续盘问
            """
            InvitationAction.WAIT -> """
                场景：刚邀请过 对方只说最近忙 没有新信息
                普通版：行 你先忙
                场景：对方正在认真倾诉
                先接她正在说的具体事情 不把见面当成解决所有问题的办法
            """
            InvitationAction.STOP -> """
                场景：对方明确说暂时不想见面 不要再约
                普通版：好 那先不约了
                只承接边界 不用玩笑否定对方 也不换活动继续约
            """
        }
        return "以下仅为虚构场景范文 不是当前联系人经历或用户已做安排 不可抄成事实\n" +
            "允许参考接球 补约账本等表达 有共同玩笑语境才用 每组最多一条这样写 其余普通直说\n" +
            cases.trimIndent() + "\n只学处理方法和语气 不照抄固定句子或挪用地点日期\n"
    }
}
