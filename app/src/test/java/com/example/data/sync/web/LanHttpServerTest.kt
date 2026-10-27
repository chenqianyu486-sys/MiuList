package com.example.data.sync.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 局域网 HTTP 内核。
 *
 * 这些用例**真的开一个 socket**（端口 0 = 随机端口），
 * 因为这层代码的价值全在「字节怎么发出去」上 ——
 * 拿 `HttpRequest` 直接构造去测，测的就只是构造器，不是这层。
 */
class LanHttpServerTest {

    // 注意：`handler` 必须是**最后一个参数**。
    // 否则调用方写成 `startServer { ... }` 时，
    // 尾随的 lambda 会绑到最后一个参数上（那时是 allowedOrigins）。
    private fun startServer(
        allowedOrigins: Set<String> = emptySet(),
        handler: (HttpRequest) -> HttpReply,
    ): Pair<LanHttpServer, Int> {
        val server = LanHttpServer(
            port = 0,
            handler = handler,
            allowedOrigins = { allowedOrigins },
        )
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
                ("GET / HTTP/1.1\r\nHost: 127.0.0.1\r\nOrigin: http://127.0.0.1:8777\r\n\r\n")
                    .toByteArray()
            )
            out.flush()
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            var line = reader.readLine()
            var sawCors = false
            while (line != null && line.isNotEmpty()) {
                if (line.startsWith("Access-Control-Allow-Origin", ignoreCase = true)) {
                    sawCors = line.contains("http://127.0.0.1:8777")
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
    fun `工作池拒绝任务不会终止监听循环或泄漏连接`() {
        val pool = ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
        )
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val server = LanHttpServer(
            port = 0,
            handler = {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                HttpReply.text(200, "ok")
            },
            workers = pool,
        )
        val port = server.start()
        val sockets = mutableListOf<Socket>()
        try {
            fun openBlockedRequest(): Socket {
                val socket = Socket("127.0.0.1", port)
                socket.soTimeout = 3000
                socket.getOutputStream().write(
                    "GET /api/health HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray()
                )
                socket.getOutputStream().flush()
                sockets.add(socket)
                return socket
            }

            openBlockedRequest()
            assertTrue("第一个请求应占住唯一的工作线程", entered.await(2, TimeUnit.SECONDS))
            openBlockedRequest()
            val queued = waitUntil(2000) { pool.queue.size == 1 }
            assertTrue("第二个请求应进入队列", queued)

            // 第三个连接会被有界池拒绝：它必须被服务端关掉，而不是留着。
            val rejected = openBlockedRequest()
            val closed = try {
                rejected.getInputStream().read()
                true
            } catch (_: java.net.SocketTimeoutException) {
                false
            } catch (_: java.io.IOException) {
                true
            }
            assertTrue("被拒绝的连接必须关闭，不能泄漏", closed)
            assertTrue(
                "拒绝任务不能把 accept 线程带走",
                Thread.getAllStackTraces().keys.any { it.name == "miulist-web-accept" && it.isAlive },
            )

            // 放开工作线程后，服务仍然可用 —— 监听循环确实还活着。
            release.countDown()
            val (code, _) = get(port, "/api/health")
            assertEquals(200, code)
        } finally {
            release.countDown()
            sockets.forEach { runCatching { it.close() } }
            server.stop()
            pool.shutdownNow()
        }
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
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

    // ── 跨源（网页端跑在电脑上时） ──────────────────────────────
    //
    // 网页端跑在电脑上时，页面在 `127.0.0.1`，接口在手机的 `192.168.x.x` ——
    // **跨源**。于是浏览器会先发 OPTIONS 预检，并且只把「CORS 安全列表」里的
    // 响应头交给脚本。下面三个用例守的就是这条路上的三道坎：
    // 预检放行、响应头暴露、SSE 也带 CORS。
    //
    // 三道坎缺任何一道，症状都是「同源一切正常，跨源就是连不上」。

    /** 裸 socket 发一条请求，把响应头读回来。 */
    private fun rawHeaders(port: Int, request: String): Map<String, String> {
        val socket = Socket("127.0.0.1", port)
        socket.soTimeout = 3000
        val out = socket.getOutputStream()
        out.write(request.toByteArray(StandardCharsets.UTF_8))
        out.flush()
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
        val headers = LinkedHashMap<String, String>()
        var line = reader.readLine()
        while (line != null && line.isNotEmpty()) {
            val idx = line.indexOf(':')
            if (idx > 0) {
                headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
            line = reader.readLine()
        }
        socket.close()
        return headers
    }

    @Test
    fun `跨源预检要放行签名头 - 少一个网页端就连不上`() {
        val (server, port) = startServer { HttpReply.text(200, "ok") }
        try {
            val headers = rawHeaders(
                port,
                "OPTIONS /api/snapshot HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
                    "Origin: http://127.0.0.1:8777\r\n" +
                    "Access-Control-Request-Method: GET\r\n" +
                    "Access-Control-Request-Headers: x-ml-device,x-ml-ts,x-ml-nonce,x-ml-sig\r\n\r\n"
            )
            val allow = headers["access-control-allow-headers"].orEmpty()
            for (name in listOf("X-ML-Device", "X-ML-Ts", "X-ML-Nonce", "X-ML-Sig", "X-ML-Enc")) {
                assertTrue("预检必须放行 $name，实际是：$allow", allow.contains(name))
            }
            assertEquals("http://127.0.0.1:8777", headers["access-control-allow-origin"])
        } finally {
            server.stop()
        }
    }

    @Test
    fun `要暴露密文标记 - 跨源时浏览器看不见自定义响应头`() {
        val (server, port) = startServer { HttpReply.text(200, "ok") }
        try {
            val headers = rawHeaders(
                port,
                "GET /api/snapshot HTTP/1.1\r\nHost: 127.0.0.1\r\nOrigin: http://127.0.0.1:8777\r\n\r\n"
            )
            assertEquals(
                "X-ML-Enc",
                headers["access-control-expose-headers"],
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun `SSE 也要带 CORS 头 - 否则跨源时这条流被浏览器掐掉`() {
        val (server, port) = startServer { _ ->
            HttpReply.EventStream { sink -> sink.send("hello", "{}") }
        }
        try {
            val headers = rawHeaders(
                port,
                "GET /api/events HTTP/1.1\r\nHost: 127.0.0.1\r\nOrigin: http://127.0.0.1:8777\r\n\r\n"
            )
            assertEquals("http://127.0.0.1:8777", headers["access-control-allow-origin"])
        } finally {
            server.stop()
        }
    }

    // ── 跨源来源闸门（协议 v3.2 起） ─────────────────────────────
    //
    // 原来的实现是「**无条件回显请求的 Origin**」——
    // 那等于对**互联网上的任意网页**开放这个局域网接口，
    // 而其中 `/api/pair/request` 是**免鉴权**的：
    // 恶意网页可以直接凭空造一条配对请求，骗用户在手机上点「允许」。
    //
    // v3.2 改成白名单，白名单外**直接 403**。
    //
    // 注意这不是「不给 CORS 响应头」那种软处理 ——
    // 后者只挡得住「读响应」，挡不住「把请求发出去」，
    // 而 `/api/pair/request` 恰恰是**不需要读响应**就能生效的。

    /** 裸 socket 发一条请求，把状态码、响应头与响应体都读回来。 */
    private fun rawExchange(port: Int, request: String): Triple<Int, Map<String, String>, String> {
        val socket = Socket("127.0.0.1", port)
        socket.soTimeout = 3000
        val out = socket.getOutputStream()
        out.write(request.toByteArray(StandardCharsets.UTF_8))
        out.flush()
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
        val statusLine = reader.readLine().orEmpty()
        val code = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: 0
        val headers = LinkedHashMap<String, String>()
        var line = reader.readLine()
        while (line != null && line.isNotEmpty()) {
            val idx = line.indexOf(':')
            if (idx > 0) {
                headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
            line = reader.readLine()
        }
        // 服务端发完就关连接（`Connection: close`），所以读到 EOF 就是读完。
        val body = reader.readText()
        socket.close()
        return Triple(code, headers, body)
    }

    @Test
    fun `白名单外的 Origin 直接 403 - handler 根本不会被调用`() {
        var handlerCalled = false
        val (server, port) = startServer(
            handler = {
                handlerCalled = true
                HttpReply.text(200, "ok")
            },
            allowedOrigins = setOf("http://192.168.1.7:8765"),
        )
        try {
            val (code, headers, _) = rawExchange(
                port,
                "GET /api/health HTTP/1.1\r\nHost: 127.0.0.1\r\nOrigin: http://evil.example.com\r\n\r\n",
            )
            assertEquals(403, code)
            assertFalse(
                "403 的响应里不能有 Access-Control-Allow-Origin",
                headers.containsKey("access-control-allow-origin"),
            )
            assertFalse("白名单外的请求根本不该进 handler", handlerCalled)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `file 单文件的 Origin 是 null - 要放行`() {
        // 桌面端是**本地单文件 HTML**，双击打开就是 file://，
        // 浏览器给这种页面发请求时 Origin 头是字符串 "null"。
        // 不放行它，桌面端就彻底连不上手机了。
        val (server, port) = startServer { HttpReply.text(200, "ok") }
        try {
            val (code, headers, _) = rawExchange(
                port,
                "GET /api/health HTTP/1.1\r\nHost: 127.0.0.1\r\nOrigin: null\r\n\r\n",
            )
            assertEquals(200, code)
            assertEquals("null", headers["access-control-allow-origin"])
        } finally {
            server.stop()
        }
    }

    @Test
    fun `没有端口的 localhost Origin 也要放行`() {
        val (server, port) = startServer { HttpReply.text(200, "ok") }
        try {
            for (origin in listOf("http://localhost", "http://127.0.0.1", "http://localhost:3000")) {
                val (code, headers, _) = rawExchange(
                    port,
                    "GET /api/health HTTP/1.1\r\nHost: 127.0.0.1\r\nOrigin: $origin\r\n\r\n",
                )
                assertEquals("$origin 应放行", 200, code)
                assertEquals(origin, headers["access-control-allow-origin"])
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `长得像 localhost 的其他主机仍要拒绝`() {
        val (server, port) = startServer { HttpReply.text(200, "ok") }
        try {
            for (origin in listOf("http://localhost.evil.example", "http://127.0.0.1.evil.example")) {
                val (code, _, _) = rawExchange(
                    port,
                    "GET /api/health HTTP/1.1\r\nHost: 127.0.0.1\r\nOrigin: $origin\r\n\r\n",
                )
                assertEquals("$origin 不是本机，必须 403", 403, code)
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `手机自己的局域网地址要放行 - 网页端也可能由手机托管`() {
        val (server, port) = startServer(
            handler = { HttpReply.text(200, "ok") },
            allowedOrigins = setOf("http://192.168.1.7:8765"),
        )
        try {
            val (code, headers, _) = rawExchange(
                port,
                "GET /api/health HTTP/1.1\r\nHost: 127.0.0.1\r\nOrigin: http://192.168.1.7:8765\r\n\r\n",
            )
            assertEquals(200, code)
            assertEquals("http://192.168.1.7:8765", headers["access-control-allow-origin"])
        } finally {
            server.stop()
        }
    }

    @Test
    fun `没有 Origin 头的请求照常放行 - curl 与原生客户端都不带`() {
        val (server, port) = startServer { HttpReply.text(200, "ok") }
        try {
            val (code, _, _) = rawExchange(port, "GET /api/health HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
            assertEquals(200, code)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `来源地址是服务端观察到的 - 客户端自报的头一律不作数`() {
        // 确认卡上给用户看的「来源地址」必须是服务端从 TCP 连接上读出来的：
        // 客户端自报的 deviceName 谁都能写成「我的电脑」，
        // 而 X-Forwarded-For 之类同样可以随手填。
        val (server, port) = startServer { req -> HttpReply.text(200, "peer=${req.remoteAddress}") }
        try {
            val (code, _, body) = rawExchange(
                port,
                "GET /api/health HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
                    "X-Forwarded-For: 1.2.3.4\r\nX-Real-IP: 1.2.3.4\r\n\r\n",
            )
            assertEquals(200, code)
            assertTrue(body, body.contains("peer=127.0.0.1:"))
            assertFalse(body, body.contains("1.2.3.4"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `Content-Length 超上限直接 413 - 不能让未鉴权的对端把内存吃光`() {
        // 原来这里是 `ByteArray(length)` —— 未鉴权的远端只要发一个
        // `Content-Length: 2000000000` 的头，就能让 App 直接 OOM 崩掉。
        val (server, port) = startServer { HttpReply.text(200, "ok") }
        try {
            val (code, _, _) = rawExchange(
                port,
                "POST /api/pair/request HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
                    "Content-Length: 999999999\r\n\r\n",
            )
            assertEquals(413, code)
        } finally {
            server.stop()
        }
    }
}
