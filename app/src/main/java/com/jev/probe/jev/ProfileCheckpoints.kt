package com.jev.probe.jev

import android.content.Context
import com.jev.probe.core.kb.RelationshipSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Only validated model JSON is cached, never credentials or a second copy of raw history. */
interface ProfileCheckpoints {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun retain(keys: Set<String>)
}

internal fun profileDigest(value: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    val hex = "0123456789abcdef"
    return CharArray(bytes.size * 2) { index ->
        val byte = bytes[index / 2].toInt() and 255
        hex[if (index % 2 == 0) byte ushr 4 else byte and 15]
    }.concatToString()
}

/** Includes corrections to speaker/time/text even when the row count has not changed. */
fun RelationshipSource.fingerprint(): String = profileDigest(JSONArray().apply {
    put(app)
    lines.forEach { line ->
        put(JSONArray(listOf(line.ref, line.app, line.side, line.kind, line.text,
            line.visibleTime, line.timeConfidence, line.historical)))
    }
}.toString())

/**
 * Atomic, app-private, excluded from Android backup. Contact/App/route are independent.
 * Clear invalidates open sessions, so a late network response cannot resurrect deleted data.
 */
class FileProfileCheckpoints(context: Context, contactId: String, app: String, route: String) : ProfileCheckpoints {
    private val folder = File(File(context.noBackupFilesDir, "profile-checkpoints"), profileDigest(contactId))
    private val identity = folder.absolutePath
    private val file = File(folder, profileDigest("$app\n$route") + ".json")
    private val epoch: Long
    private val entries: LinkedHashMap<String, String>

    init {
        synchronized(lock) {
            epoch = epochs[identity] ?: 0L
            entries = runCatching {
                val json = JSONObject(file.readText(Charsets.UTF_8))
                linkedMapOf<String, String>().apply {
                    if (json.optInt("version") == 1) {
                        val nodes = json.getJSONObject("nodes")
                        nodes.keys().forEach { key ->
                            val value = nodes.optString(key)
                            if (runCatching { JSONObject(value) }.isSuccess) put(key, value)
                        }
                    }
                }
            }.getOrElse { linkedMapOf() }
        }
    }

    private fun checkActive() {
        check((epochs[identity] ?: 0L) == epoch) { "聊天记忆已清理或关闭，请重新开始生成" }
        if (Thread.currentThread().isInterrupted) throw InterruptedException("档案生成已取消")
    }

    override fun get(key: String): String? = synchronized(lock) {
        checkActive()
        entries[key]
    }

    override fun put(key: String, value: String) = synchronized(lock) {
        checkActive()
        JSONObject(value) // Never persist malformed output.
        if (entries[key] == value) return@synchronized
        entries[key] = value
        // Bounded even when users repeatedly cancel or change records before completion.
        while (entries.size > 4096) entries.remove(entries.keys.first())
        persist()
    }

    override fun retain(keys: Set<String>) = synchronized(lock) {
        checkActive()
        if (entries.keys.retainAll(keys)) persist()
    }

    private fun persist() {
        check(folder.isDirectory || folder.mkdirs()) { "无法创建档案断点目录" }
        val pending = File(file.path + ".tmp")
        FileOutputStream(pending).use { stream ->
            val nodes = JSONObject().apply { entries.forEach { (key, value) -> put(key, value) } }
            stream.write(JSONObject().put("version", 1).put("nodes", nodes)
                .toString().toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        // Same filesystem atomic replace; unlike File.renameTo it reports write failures
        // and supports replacing existing files in Windows-hosted regression tests too.
        Files.move(pending.toPath(), file.toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        private val lock = Any()
        private val epochs = HashMap<String, Long>()

        fun clearContact(context: Context, contactId: String) = synchronized(lock) {
            val folder = File(File(context.noBackupFilesDir, "profile-checkpoints"), profileDigest(contactId))
            epochs[folder.absolutePath] = (epochs[folder.absolutePath] ?: 0L) + 1L
            // This directory is derived only from a SHA-256 contact ID, beneath our private root.
            check(!folder.exists() || folder.deleteRecursively()) { "档案断点清理失败" }
        }
    }
}
