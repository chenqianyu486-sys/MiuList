package com.example.data.sync.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * 局域网 HTTP 内核。
 *
 * 这些用例**真的开一个 socket**（端口 0 = 随机端口），
 * 因为这层代码的价值全在「字节怎么发出去」上 ——
 * 拿 `HttpRequest` 直接构造去测，测的就只是构造器，不是这层。
 */
class LanHttpServerTest {

    private fun startServer(handler: (HttpRequest) -> HttpReply): Pair<LanHttpServer, Int> {
        val server = LanHttpServer(port = 0, handler = handler)
        val port = server.start()
        return server to port
    }

    private fun get(port: Int, path: String, headers: Map<String, String> = emptyMap()): Pair<Int, String> {
        val conn = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        val code = conn.responseCode
        val text = if (code < 400) {
            conn.inputStream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
        } else {
            conn.errorStream?.use { it.readBytes().toString(StandardCharsets.UTF_8) } ?: ""
        }
        conn.disconnect()
        return code to text
    }

    @Test
    fun `解析请求行 路径 查询串与头`() {
        val (server, port) = startServer { req ->
            HttpReply.text(
                200,
                "method=${req.method} path=${req.path} q=${req.query["a"]} auth=${req.header("authorization")}"
            )
        }
        try {
            val (code, body) = get(port, "/api/x?a=hello%20world", mapOf("Authorization" to "Bearer t"))
            assertEquals(200, code)
            assertTrue(body, body.contains("method=GET"))
            assertTrue(body, body.contains("path=/api/x"))
            assertTrue(body, body.contains("q=hello world"))
            assertTrue(body, body.contains("auth=Bearer t"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `token 也可以走查询参数 - EventSource 不能设请求头`() {
        val (server, port) = startServer { req ->
            HttpReply.text(200, "token=${req.bearerToken()}")
        }
        try {
            val (_, body) = get(port, "/api/events?token=abc123")
            assertTrue(body, body.contains("abc123"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `请求体按 Content-Length 读出来`() {
        val (server, port) = startServer { req ->
            HttpReply.text(200, "body=${req.bodyText()}")
        }
        try {
            val conn = URL("http://127.0.0.1:$port/api/tasks").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val payload = """{"title":"中文也要对"}""".toByteArray(StandardCharsets.UTF_8)
            conn.setFixedLengthStreamingMode(payload.size)
            conn.outputStream.use { it.write(payload) }
            val code = conn.responseCode
            val body = conn.inputStream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
            conn.disconnect()
            assertEquals(200, code)
            assertTrue(body, body.contains("中文也要对"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `响应带上 CORS 头 - 手动输入地址那条路是跨源的`() {
        val (server, port) = startServer { HttpReply.text(200, "ok") }
        try {
            // **不能用 HttpURLConnection 发这个请求**：Java 的 HttpURLConnection
            // 有一张「受限头」名单，Origin 就在里面 —— 它会被**静默丢掉**，
            // 于是服务端根本收不到 Origin，断言就成了「CORS 头没写」。
            // 用裸 socket 才能把 Origin 原样送出去。
            val socket = Socket("127.0.0.1", port)
            socket.soTimeout = 3000
            val out = socket.getOutputStream()
            out.write(
                ("GET / HTTP/1.1\r\nHost: 127.0.0.1\r\nOrigin: http://192.168.1.9:8777\r\n\r\n")
                    .toByteArray()
            )
            out.flush()
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            var line = reader.readLine()
            var sawCors = false
            while (line != null && line.isNotEmpty()) {
                if (line.startsWith("Access-Control-Allow-Origin", ignoreCase = true)) {
                    sawCors = line.contains("http://192.168.1.9:8777")
                }
                line = reader.readLine()
            }
            socket.close()
            assertTrue("响应里要有 Access-Control-Allow-Origin，且回显请求的 Origin", sawCors)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `SSE 是一条一直开着的连接 - 事件能推出去`() {
        val (server, port) = startServer { _ ->
            HttpReply.EventStream { sink ->
                sink.send("hello", """{"revision":1}""")
                sink.send("change", """{"revision":2}""")
            }
        }
        try {
            val socket = Socket("127.0.0.1", port)
            socket.soTimeout = 3000
            // **不要 use { } 关掉输出流**：那会把整个 socket 关掉，
            // 服务端接着写事件时就是「对端已关闭」，
            // 症状是读事件读到「Socket is closed」，看着像服务端的问题。
            val out = socket.getOutputStream()
            out.write("GET /api/events HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
            out.flush()
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            val status = reader.readLine()
            assertTrue(status, status.contains("200"))
            var contentType: String? = null
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Content-Type", ignoreCase = true)) contentType = line
            }
            assertTrue("SSE 的 Content-Type 必须是 text/event-stream", contentType!!.contains("text/event-stream"))
            // 事件体。两个事件各是「event 行 + data 行 + 空行」。
            //
            // **必须读到第二个事件的 data 行就停**：SSE 连接是常开的，
            // 一直读到「攒够 N 个字符」会在事件读完之后继续等下一行，
            // 而下一行永远不来 —— 表现是读超时，而不是「少读了一行」。
            var body = StringBuilder()
            while (body.length < 400) {
                val line = reader.readLine() ?: break
                body.append(line).append('\n')
                if (body.contains("event: change") && line.startsWith("data:")) break
            }
            val text = body.toString()
            assertTrue(text, text.contains("event: hello"))
            assertTrue(text, text.contains("""data: {"revision":1}"""))
            assertTrue(text, text.contains("event: change"))
            socket.close()
        } finally {
            server.stop()
        }
    }

    @Test
    fun `handler 抛异常时返回 500 而不是把连接掐了`() {
        val (server, port) = startServer { throw IllegalStateException("boom") }
        try {
            val (code, body) = get(port, "/api/health")
            assertEquals(500, code)
            assertTrue(body, body.contains("boom"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `停掉之后端口不再接受连接`() {
        val (server, port) = startServer { HttpReply.text(200, "ok") }
        get(port, "/api/health")
        server.stop()
        // 端口被释放；连不上是预期结果。
        val refused = try {
            Socket("127.0.0.1", port).use { true }
        } catch (_: Exception) {
            false
        }
        assertTrue("停掉之后不应该还能连上", !refused)
    }

    @Test
    fun `端口用 0 时由系统分配 - 不会撞上已经在用的端口`() {
        val (a, portA) = startServer { HttpReply.text(200, "a") }
        val (b, portB) = startServer { HttpReply.text(200, "b") }
        try {
            assertTrue(portA > 0)
            assertTrue(portB > 0)
            assertTrue("两个服务不能抢同一个端口", portA != portB)
        } finally {
            a.stop()
            b.stop()
        }
    }
}
