package com.example.data.sync.web

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一个请求。
 *
 * **自己解析，不依赖任何 HTTP 库。** 这不是「造轮子」的美学问题：
 * NanoHTTPD / Ktor server 都要么已经停更、要么把 Netty 拖进 APK（几 MB，
 * 还带一整套 Android 上用不上的能力），而这里真正需要的只有三件事 ——
 * 解析请求行与头、读一个 `Content-Length` 的 body、能把响应**流式**写出去
 * （SSE 要的就是这个）。这三百行能覆盖，且能在普通 JUnit 里测。
 */
class HttpRequest(
    val method: String,
    /** 不含查询串的路径。 */
    val path: String,
    val query: Map<String, String>,
    /** 头名一律小写。 */
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    /**
     * 取一个请求头。**大小写不敏感。**
     *
     * HTTP 的头名本来就不区分大小写，而构造 [HttpRequest] 的人未必会把键都写成小写 ——
     * 「查的时候小写、存的时候大写」这种不对称会让 `Authorization` 莫名其妙取不到，
     * 而症状是 401，看起来像「token 错了」而不是「键名大小写不对」。
     */
    fun header(name: String): String? {
        headers[name]?.let { return it }
        val lower = name.lowercase()
        return headers.entries.firstOrNull { it.key.lowercase() == lower }?.value
    }

    fun bodyText(): String = body.toString(Charsets.UTF_8)

    /**
     * Bearer token。
     *
     * **同时接受 `?token=`** —— EventSource 不能设请求头，SSE 只能走查询参数。
     * 这是唯一的例外，别的接口仍然用 `Authorization` 头。
     */
    fun bearerToken(): String? {
        val h = header("authorization")?.trim()
        if (h != null && h.startsWith("Bearer ", ignoreCase = true)) {
            return h.substring(7).trim().takeIf { it.isNotEmpty() }
        }
        return query["token"]?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/** 一个响应。 */
sealed class HttpReply {

    /** 一次性响应。 */
    class Full(
        val status: Int,
        val contentType: String,
        val body: ByteArray,
        val headers: Map<String, String> = emptyMap(),
    ) : HttpReply()

    /**
     * 一个**长连接**的响应（SSE）。
     *
     * [subscribe] 在响应头写出去之后被调用；它拿到 [EventSink]，
     * 往里写事件。服务端会一直保持连接，直到 [EventSink.close] 被调用。
     */
    class EventStream(val subscribe: (EventSink) -> Unit) : HttpReply()

    interface EventSink {
        fun send(event: String, data: String)
        fun close()
    }

    companion object {
        fun json(status: Int, body: String): Full =
            Full(status, "application/json; charset=utf-8", body.toByteArray(Charsets.UTF_8))

        fun text(status: Int, body: String, contentType: String = "text/plain; charset=utf-8"): Full =
            Full(status, contentType, body.toByteArray(Charsets.UTF_8))
    }
}

/**
 * 局域网 HTTP 服务。
 *
 * ## 为什么自己写
 *
 * 见 [HttpRequest] 的注释。要的能力只有「解析 + 静态响应 + SSE 流」，
 * 换来的是零新依赖（APK 不变大）、零 Android 兼容问题、能在 JUnit 里直接测。
 *
 * ## 并发模型
 *
 * 每个连接一个线程（`newCachedThreadPool`）。SSE 客户端会**占住**一个线程直到
 * 断开为止 —— 局域网里同时挂着三五个浏览器页面完全够用，而换成异步 IO
 * 只会让这份代码难读三倍。线程都是 daemon，进程退出时不会挂着。
 *
 * ## 传输约定
 *
 * 一律 `Connection: close`。keep-alive 要处理「客户端复用连接时刚好服务端
 * 已经关了」这类边角，而这里没有任何一个客户端需要复用 —— 浏览器的
 * EventSource 自己会重连，普通请求本来就是一次性的。
 */
class LanHttpServer(
    private val port: Int = 0,
    private val handler: (HttpRequest) -> HttpReply,
) {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val workers = Executors.newCachedThreadPool { r ->
        Thread(r, "miulist-web").apply { isDaemon = true }
    }

    @Volatile
    var boundPort: Int = -1
        private set

    /** 启动并阻塞到监听就绪。返回实际绑定的端口。 */
    fun start(): Int {
        if (running.getAndSet(true)) return boundPort
        val socket = ServerSocket(port)
        socket.reuseAddress = true
        serverSocket = socket
        boundPort = socket.localPort
        Thread({
            while (running.get()) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    // stop() 关闭 socket 会让 accept 抛出来，这是正常退出路径。
                    if (running.get()) continue else break
                }
                workers.execute { serve(client) }
            }
        }, "miulist-web-accept").apply { isDaemon = true }.start()
        return boundPort
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        try { serverSocket?.close() } catch (_: IOException) { }
        serverSocket = null
        workers.shutdownNow()
    }

    private fun serve(socket: Socket) {
        try {
            socket.use {
                socket.soTimeout = READ_TIMEOUT_MS
                val input = BufferedInputStream(socket.getInputStream())
                val request = parseRequest(input) ?: return
                val reply = try {
                    handler(request)
                } catch (e: Exception) {
                    HttpReply.json(500, """{"error":"服务器内部错误：${escapeJson(e.message ?: e::class.java.simpleName)}"}""")
                }
                when (reply) {
                    is HttpReply.Full -> writeFull(socket, request, reply)
                    is HttpReply.EventStream -> writeEventStream(socket, reply)
                }
            }
        } catch (_: Exception) {
            // 客户端半路断开是常事（刷新页面、切网），不必上报。
        }
    }

    // ── 请求解析 ────────────────────────────────────────────────

    private fun parseRequest(input: InputStream): HttpRequest? {
        val requestLine = readLine(input) ?: return null
        if (requestLine.isBlank()) return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        val method = parts[0].uppercase()
        val rawTarget = parts[1]

        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }

        val path = rawTarget.substringBefore('?')
        val query = parseQuery(rawTarget.substringAfter('?', ""))

        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (length > 0) {
            val buf = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(buf, read, length - read)
                if (n < 0) break
                read += n
            }
            buf.copyOf(read)
        } else {
            ByteArray(0)
        }

        return HttpRequest(method, path, query, headers, body)
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream(128)
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString(Charsets.ISO_8859_1)
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
            if (buf.size() > MAX_LINE_BYTES) return null
        }
        return buf.toString(Charsets.ISO_8859_1)
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in raw.split('&')) {
            if (pair.isBlank()) continue
            val k = pair.substringBefore('=')
            val v = pair.substringAfter('=', "")
            out[decode(k)] = decode(v)
        }
        return out
    }

    private fun decode(s: String): String =
        try {
            java.net.URLDecoder.decode(s, "UTF-8")
        } catch (_: Exception) {
            s
        }

    // ── 响应 ────────────────────────────────────────────────────

    private fun writeFull(socket: Socket, request: HttpRequest, reply: HttpReply.Full) {
        val out = BufferedOutputStream(socket.getOutputStream())
        val head = StringBuilder()
        head.append("HTTP/1.1 ").append(reply.status).append(' ').append(statusText(reply.status)).append("\r\n")
        head.append("Content-Type: ").append(reply.contentType).append("\r\n")
        head.append("Content-Length: ").append(reply.body.size).append("\r\n")
        head.append("Connection: close\r\n")
        appendCors(head, request)
        for ((k, v) in reply.headers) head.append(k).append(": ").append(v).append("\r\n")
        head.append("\r\n")
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        out.write(reply.body)
        out.flush()
    }

    private fun writeEventStream(socket: Socket, reply: HttpReply.EventStream) {
        val out = BufferedOutputStream(socket.getOutputStream())
        val head = StringBuilder()
        head.append("HTTP/1.1 200 OK\r\n")
        head.append("Content-Type: text/event-stream; charset=utf-8\r\n")
        head.append("Cache-Control: no-cache\r\n")
        head.append("Connection: close\r\n")
        head.append("\r\n")
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        out.flush()

        val queue = LinkedBlockingQueue<String?>()
        val closed = CountDownLatch(1)
        val sink = object : HttpReply.EventSink {
            override fun send(event: String, data: String) {
                queue.offer("event: $event\ndata: $data\n\n")
            }

            override fun close() {
                queue.offer(null)
                closed.countDown()
            }
        }
        reply.subscribe(sink)

        try {
            while (true) {
                val msg = queue.take() ?: break
                out.write(msg.toByteArray(Charsets.UTF_8))
                out.flush()
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * CORS：**回显**请求的 Origin，不写 `*`。
     *
     * 为什么要 CORS：网页端支持「手动输入地址」——页面可能开在 A 地址，
     * API 却在 B 地址，那是跨源的。
     *
     * 为什么回显而不是 `*`：写 `*` 等于让**任何**网页都能读本机的这个 API。
     * 数据接口本身要 Bearer token（网页端不会把 token 交给别的站点），
     * 但 `/api/pair/request` 是免鉴权的 —— `*` 会让别人随便申请配对码。
     * 回显 Origin 至少把「能不能读」绑在同源上。
     */
    private fun appendCors(head: StringBuilder, request: HttpRequest) {
        val origin = request.header("origin") ?: return
        head.append("Access-Control-Allow-Origin: ").append(origin).append("\r\n")
        head.append("Access-Control-Allow-Methods: GET, POST, PATCH, DELETE, OPTIONS\r\n")
        head.append("Access-Control-Allow-Headers: Authorization, Content-Type\r\n")
        head.append("Access-Control-Max-Age: 600\r\n")
        head.append("Vary: Origin\r\n")
    }

    companion object {
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_LINE_BYTES = 16 * 1024

        fun statusText(status: Int): String = when (status) {
            200 -> "OK"
            201 -> "Created"
            204 -> "No Content"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            409 -> "Conflict"
            413 -> "Payload Too Large"
            500 -> "Internal Server Error"
            else -> "Status"
        }

        fun escapeJson(s: String): String = buildString {
            for (c in s) {
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
                }
            }
        }
    }
}
