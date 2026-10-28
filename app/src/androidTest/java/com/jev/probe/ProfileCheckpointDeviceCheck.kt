package com.jev.probe

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import com.jev.probe.core.kb.ProfileLine
import com.jev.probe.core.kb.RelationshipSource
import com.jev.probe.jev.FileProfileCheckpoints
import com.jev.probe.jev.RelationshipProfileGenerator
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/** Separate test APK: no chat access, network, contact writes, or production profile updates. */
class ProfileCheckpointDeviceCheck : Instrumentation() {
    private var phase = ""
    override fun onCreate(arguments: Bundle?) {
        phase = arguments?.getString("phase").orEmpty()
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = ProfileCheckpointScenario.run(targetContext, phase)
        finish(if (result.getString("result").orEmpty().startsWith("PASS")) -1 else 0, result)
    }
}

/** run-as/app_process entry for devices that disallow installing a separate instrumentation APK. */
object ProfileCheckpointShellCheck {
    @JvmStatic fun main(args: Array<String>) {
        val context = object : ContextWrapper(null) {
            override fun getNoBackupFilesDir() = java.io.File(
                "/data/user/0/com.jev.personal.chatassistant/no_backup")
        }
        val result = ProfileCheckpointScenario.run(context, args.single())
        result.keySet().sorted().forEach { println("$it=${result.get(it)}") }
        check(result.getString("result").orEmpty().startsWith("PASS"))
    }
}

private object ProfileCheckpointScenario {
    fun run(context: Context, phase: String): Bundle {
        val result = Bundle()
        val id = "checkpoint-device-selftest"
        val app = "test.synthetic.chat"
        fun source(count: Int) = RelationshipSource(app, 30, (1..count).map {
            ProfileLine("db:$it", if (it % 2 == 0) "other" else "me",
                "Synthetic message $it", it.toLong(), app, "TEXT", null, "unknown", true)
        }, 1, count.toLong())
        val calls = AtomicInteger()
        try {
            fun generate(count: Int, failFinal: Boolean = false) =
                RelationshipProfileGenerator({ _, user, _ ->
                    calls.incrementAndGet()
                    if (failFinal && user.startsWith("任务：将下方")) error("simulated network interruption")
                    val digest = MessageDigest.getInstance("SHA-256").digest(user.toByteArray())
                    JSONObject().put("evidenceDigest", android.util.Base64.encodeToString(digest, 2)).toString()
                }, parallelism = 1, checkpoints = FileProfileCheckpoints(
                    context, id, app, "test-no-network")).generate(source(count))
            when (phase) {
                "seed" -> {
                    FileProfileCheckpoints.clearContact(context, id)
                    check(runCatching { generate(14000, true) }.isFailure)
                    check(calls.get() > 10)
                    result.putInt("seed_calls", calls.get())
                }
                "resume" -> {
                    generate(14000)
                    check(calls.get() == 1) { "Expected final only, actual ${calls.get()}" }
                    result.putInt("resume_calls", calls.get())
                    calls.set(0)
                    generate(14000)
                    check(calls.get() == 0)
                    result.putInt("unchanged_calls", calls.get())
                    generate(14001)
                    check(calls.get() in 2..10) { "Unexpected incremental count ${calls.get()}" }
                    result.putInt("incremental_calls", calls.get())
                    FileProfileCheckpoints.clearContact(context, id)
                }
                else -> error("Expected seed/resume")
            }
            result.putString("result", "PASS synthetic local checkpoint test; no network or contact changes")
        } catch (e: Exception) {
            result.putString("result", "FAIL ${e.javaClass.simpleName}: ${e.message}")
        }
        return result
    }
}
