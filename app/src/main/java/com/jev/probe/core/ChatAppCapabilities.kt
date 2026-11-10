package com.jev.probe.core

/** Keep feature entry gates aligned. Adding an adapter alone does not enable memory. */
internal object ChatAppCapabilities {
    const val QQ = "com.tencent.mobileqq"
    val suggestions = setOf("com.tencent.mm", "com.tantan.x", "cn.soulapp.android", "cn.neoclub.uki",
        "com.wepie.ivy", "com.yixiangweilai.yuebaobao", "com.pingzishuo.www", QQ)
    val automaticMemory = setOf("com.tencent.mm", "com.tantan.x", "cn.soulapp.android", "com.wepie.ivy", QQ)
    val storedMemory = automaticMemory + "com.yixiangweilai.yuebaobao"
    val confirmedSpeakers = setOf("com.tencent.mm", "cn.soulapp.android", QQ)
}
