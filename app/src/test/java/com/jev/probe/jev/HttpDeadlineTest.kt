package com.jev.probe.jev

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HttpDeadlineTest {
    @Test fun slowlyTricklingBodyCannotExtendTheOverallDeadline() {
        val server = ServerSocket(0)
        val worker = thread(isDaemon = true) {
            runCatching {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    var length = 0
                    while (true) {
                        val line = input.readLine() ?: return@use
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                    }
                    repeat(length) { input.read() }
                    val output = socket.getOutputStream()
                    output.write("HTTP/1.1 200 OK\r\nContent-Length: 1000\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n".toByteArray())
                    output.flush()
                    repeat(60) {
                        output.write(' '.code)
                        output.flush()
                        Thread.sleep(80)
                    }
                }
            }
        }
        val started = System.nanoTime()
        try {
            HttpJson.post("http://127.0.0.1:${server.localPort}/", "synthetic-test-key", JSONObject(), Route.REPLY,
                timeoutMs = 400)
            fail("A trickling response must time out")
        } catch (error: ApiException) {
            assertTrue(error.snippet, error.snippet.contains("超时"))
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue("Deadline took ${elapsed}ms", elapsed < 2500)
        } finally {
            server.close()
            worker.interrupt()
        }
    }
}
