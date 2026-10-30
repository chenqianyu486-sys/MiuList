package com.example.data.sync.web

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
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
    /**
     * **服务端自己观察到的对端地址**（`ip:port`）。
     *
     * 为什么必须是服务端观察的、而不是让客户端自报：
     * 配对确认卡上要告诉用户「是谁在请求连接」——
     * 那必须是一个**客户端无法伪造**的值。
     * 自报的 `deviceName` 谁都能写成「我的电脑」。
     */

    val remoteAddress: String = "",
    /**
     * 对端 IP，**不含端口**。
     *
     * 限流必须按「来源 IP」而不是 `ip:port` —— 客户端每次新建连接的临时端口
     * 都不同，拿 `ip:port` 当键等于给每次请求发一份新配额。
     * 单独放一个字段，也避免从 `[IPv6]:port` 里用字符串截取拆地址。
     */
    val remoteHost: String = "",
    val remotePort: Int = 0,
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
 * 每个连接交给一个**有界**工作线程池（核心 4 / 最大 32 / 队列 64）。
 * SSE 客户端会**占住**一个线程直到断开为止 —— 局域网里同时挂着三五个
 * 浏览器页面完全够用。池满时新连接会被拒绝并立即关闭，accept 循环继续。
 * 线程都是 daemon，进程退出时不会挂着。
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
    /**
     * **允许跨源访问本服务的 Origin 白名单。**
     *
     * 为什么要有它：原来的实现是「**无条件回显请求的 Origin**」，
     * 那等于对**互联网上的任意网页**开放这个局域网接口 ——
     * 而其中 `/api/pair/request` 是免鉴权的，恶意网页可以直接凭空
     * 造出一条配对请求，骗用户在手机上点「允许」。
     *
     * 白名单里通常有：网页端的 `null`（file:// 单文件）、本机开发用的
     * `localhost` / `127.0.0.1`，以及手机自己的各个局域网地址。
     *
     * **带 Origin 头但不在白名单的请求会被直接 403 掉**，
     * 不只是「不给 CORS 响应头」——
     * 后者只能挡住「读取响应」，挡不住「把请求发出去」。
     */
    private val allowedOrigins: () -> Set<String> = { emptySet() },
    /**
     * 工作线程池：**有界**。
     *
     * 核心 4 / 最大 32 / 队列 64。队列满了就**拒绝** ——
     * 被拒绝的连接必须在这里就地关掉，且不能让 accept 循环退出。
     *
     * 允许注入是为了单测能用一个很小的池制造拒绝；生产路径始终用
     * [newWorkerPool] 造出来的同一套有界参数。
     */
    private val workers: ThreadPoolExecutor = newWorkerPool(),
) {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
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
                try {
                    workers.execute { serve(client) }
                } catch (_: RejectedExecutionException) {
                    // 池子满了，**就在这里**把连接关掉。
                    //
                    // 不能指望上层 catch：异常发生在这个 accept 线程里，
                    // 一路抛出去只会结束整个监听循环；而 client 还没交给
                    // 任何工作线程，不关就是泄漏。服务因此变慢，但不会停摆。
                    closeQuietly(client)
                }
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
                val peer = peerAddress(socket)
                val request = try {
                    parseRequest(input, peer)
                } catch (e: BodyTooLargeException) {
                    writeFull(socket, null, HttpReply.json(413, """{"error":"请求体太大"}"""))
                    return
                } ?: return

                // ── 跨源来源闸门 ────────────────────────────────────
                //
                // 浏览器发起的**跨源**请求一定带 `Origin` 头；
                // 同源请求、以及 curl / 原生客户端则不带。
                // 所以「带 Origin 且不在白名单」= 一律拒绝。
                //
                // 注意这不是「不给 CORS 响应头」那种软处理 ——
                // 那种只挡得住「读响应」，挡不住「把请求发出去」，
                // 而 `/api/pair/request` 恰恰是**不需要读响应**就能生效的。
                val origin = request.header("origin")
                if (origin != null && !isOriginAllowed(origin)) {
                    writeFull(socket, request, HttpReply.json(403, """{"error":"不允许的来源"}"""))
                    return
                }

                val reply = try {
                    handler(request)
                } catch (e: Exception) {
                    HttpReply.json(500, """{"error":"服务器内部错误：${escapeJson(e.message ?: e::class.java.simpleName)}"}""")
                }
                when (reply) {
                    is HttpReply.Full -> writeFull(socket, request, reply)
                    is HttpReply.EventStream -> writeEventStream(socket, request, reply)
                }
            }
        } catch (_: Exception) {
            // 客户端半路断开是常事（刷新页面、切网），不必上报。
        }
    }

    /** 对端地址：结构化保存 IP 与端口，确认卡再自己拼 `ip:port`。 */
    private data class Peer(val host: String, val port: Int) {
        /** 展示用。IPv6 加方括号，否则 `::1:8080` 分不清哪段是端口。 */
        val label: String
            get() = if (host.contains(':')) "[$host]:$port" else "$host:$port"
    }

    private fun peerAddress(socket: Socket): Peer {
        // `getRemoteSocketAddress()` 的静态类型是 SocketAddress，
        // 它**没有** port —— 得先转成 InetSocketAddress 才拿得到。
        val addr = socket.remoteSocketAddress as? InetSocketAddress
        return Peer(
            host = addr?.address?.hostAddress ?: "?",
            port = addr?.port ?: 0,
        )
    }

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (_: IOException) {
            // 已经关了 / 对端先断了，都不影响 accept 循环。
        }
    }

    /**
     * 这个 Origin 准不准跨源访问本服务。
     *
     * ## 白名单里为什么有 `null`
     *
     * 桌面端是**本地单文件 HTML**，双击打开就是 `file://` ——
     * 浏览器给这种页面发请求时，Origin 头是字符串 `null`。
     * 不放行它，桌面端就彻底连不上手机了。
     *
     * **残余风险**：沙箱 iframe（`sandbox` 且不给 `allow-same-origin`）
     * 的 Origin 也是 `null`，所以它仍能发请求。
     * 这一条挡不住，但**数据接口全都要求签名**，
     * 拿不到密钥就只能读到「有台设备在等配对」这类无害信息。
     *
     * ## 为什么 localhost 任意端口都放行
     *
     * `npm run dev` 起的本地服务器端口是变的。放行 localhost 不会让
     * 互联网上的网页冒充成 localhost —— Origin 头是浏览器写的，
     * 网页没法声明自己来自 localhost。
     */
    private fun isOriginAllowed(origin: String): Boolean {
        if (origin == "null") return true
        // 本机开发服务器：允许 localhost / 127.0.0.1 的**任意端口**（包括省略端口）。
        // 必须解析后比对 host，不能用 startsWith —— 那会把
        // `http://localhost.evil.example` 也放进白名单。
        if (isLocalHttpOrigin(origin)) return true
        return allowedOrigins().any { it.equals(origin, ignoreCase = true) }
    }

    private fun isLocalHttpOrigin(origin: String): Boolean {
        val uri = runCatching { URI(origin) }.getOrNull() ?: return false
        if (!uri.scheme.equals("http", ignoreCase = true)) return false
        val host = uri.host?.lowercase() ?: return false
        return host == "localhost" || host == "127.0.0.1"
    }

    // ── 请求解析 ────────────────────────────────────────────────

    private fun parseRequest(input: InputStream, peer: Peer): HttpRequest? {
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

        val length = headers["content-length"]?.toLongOrNull() ?: 0L
        // **先查上限，再分配。**
        //
        // 原来这里是 `ByteArray(length)` —— 一个未鉴权���远端只要发一个
        // `Content-Length: 2000000000` 的头，就能让 App 直接 OOM 崩掉。
        // 声明多长就给多少的写法在这里等于「把内存交给对端处置」。
        if (length < 0L || length > MAX_BODY_BYTES) {
            throw BodyTooLargeException()
        }
        val body = if (length > 0L) {
            val buf = ByteArray(length.toInt())
            var read = 0
            while (read < length) {
                val n = input.read(buf, read, (length - read).toInt())
                if (n < 0) break
                read += n
            }
            buf.copyOf(read)
        } else {
            ByteArray(0)
        }

        return HttpRequest(
            method,
            path,
            query,
            headers,
            body,
            remoteAddress = peer.label,
            remoteHost = peer.host,
            remotePort = peer.port,
        )
    }

    /** 请求体超过上限。单独一个类型，便于上层回 413 而不是 500。 */
    private class BodyTooLargeException : IOException("请求体太大")

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream(128)
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else String(buf.toByteArray(), Charsets.ISO_8859_1)
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
            if (buf.size() > MAX_LINE_BYTES) return null
        }
        // 不能用 buf.toString(Charsets.ISO_8859_1)：ByteArrayOutputStream 上的
        // toString(Charset) 是 API 33 才有的重载，而 minSdk=24，
        // 低版本上这里是 NoSuchMethodError（Error，不是 Exception），
        // serve() 的 catch (_: Exception) 兜不住 —— 线程直接死，连接静默断开。
        return String(buf.toByteArray(), Charsets.ISO_8859_1)
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

    private fun writeFull(socket: Socket, request: HttpRequest?, reply: HttpReply.Full) {
        val out = BufferedOutputStream(socket.getOutputStream())
        val head = StringBuilder()
        head.append("HTTP/1.1 ").append(reply.status).append(' ').append(statusText(reply.status)).append("\r\n")
        head.append("Content-Type: ").append(reply.contentType).append("\r\n")
        head.append("Content-Length: ").append(reply.body.size).append("\r\n")
        head.append("Connection: close\r\n")
        if (request != null) appendCors(head, request)
        for ((k, v) in reply.headers) head.append(k).append(": ").append(v).append("\r\n")
        head.append("\r\n")
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        out.write(reply.body)
        out.flush()
    }

    private fun writeEventStream(socket: Socket, request: HttpRequest, reply: HttpReply.EventStream) {
        val out = BufferedOutputStream(socket.getOutputStream())
        val head = StringBuilder()
        head.append("HTTP/1.1 200 OK\r\n")
        head.append("Content-Type: text/event-stream; charset=utf-8\r\n")
        head.append("Cache-Control: no-cache\r\n")
        head.append("Connection: close\r\n")
        // **SSE 也要带 CORS 头。** EventSource 是跨源的（网页端跑在电脑上时，
        // 页面在 127.0.0.1，接口在手机的 192.168.x.x），
        // 少了 Access-Control-Allow-Origin 浏览器会直接掐掉这条流 ——
        // 症状是「配对能成、数据能拉，但改动不实时推送」。
        appendCors(head, request)
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
     *
     * ## 预检要放行的头，一个都不能少
     *
     * 网页端跑在电脑上时（本机有「MiuList 网页端启动器」），页面在 `127.0.0.1`，
     * 而接口在手机的 `192.168.x.x` —— **跨源**。浏览器于是会先发一个 OPTIONS 预检，
     * 预检里 `Access-Control-Allow-Headers` 没列出的请求头会被**直接拦掉**，
     * 表现是「网页端在电脑上打开就永远连不上」。
     *
     * 签名那四个头（`X-ML-Device` / `X-ML-Ts` / `X-ML-Nonce` / `X-ML-Sig`）
     * 与密文头（`X-ML-Enc`）都在自定义头里，所以必须逐个列出来。
     */
    private fun appendCors(head: StringBuilder, request: HttpRequest) {
        val origin = request.header("origin") ?: return
        // 白名单外的 Origin **不回显** —— 上游已经 403 掉了，这里只是兜底。
        if (!isOriginAllowed(origin)) return
        head.append("Access-Control-Allow-Origin: ").append(origin).append("\r\n")
        head.append("Access-Control-Allow-Methods: GET, POST, PATCH, DELETE, OPTIONS\r\n")
        head.append("Access-Control-Allow-Headers: ")
            .append(ALLOWED_HEADERS.joinToString(", "))
            .append("\r\n")
        // **必须显式暴露 `X-ML-Enc`。**
        //
        // 跨源时浏览器只把「CORS 安全列表」里的响应头交给脚本
        // （Cache-Control、Content-Type、Content-Length、Expires、Last-Modified、Pragma），
        // 自定义头一律看不见 —— 除非服务端用 `Access-Control-Expose-Headers` 点名。
        //
        // 这个头是「**这坨是密文**」的标记：网页端靠它决定要不要解密。
        // 少了这一行，网页端会把密文当明文 JSON 解析 → 报「响应不是合法 JSON」，
        // 症状是「连上了、握手也成功了，但快照永远拉不下来」——
        // 而同源时（网页由手机托管）一切正常，极难联想到是 CORS。
        head.append("Access-Control-Expose-Headers: ").append(WebSyncApi.HEADER_ENC).append("\r\n")
        head.append("Access-Control-Max-Age: 600\r\n")
        // Chrome 的 Private Network Access：从 file:// 页面访问局域网里的手机时，
        // 预检会带 Access-Control-Request-Private-Network；必须明确放行。
        if (request.header("access-control-request-private-network") == "true") {
            head.append("Access-Control-Allow-Private-Network: true\r\n")
        }
        head.append("Vary: Origin, Access-Control-Request-Private-Network\r\n")
    }

    companion object {
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_LINE_BYTES = 16 * 1024

        /** 生产用有界池。队列满时 [ThreadPoolExecutor.execute] 抛拒绝异常。 */
        private fun newWorkerPool(): ThreadPoolExecutor =
            ThreadPoolExecutor(
                4, 32, 60L, TimeUnit.SECONDS, LinkedBlockingQueue(64),
            ) { r -> Thread(r, "miulist-web").apply { isDaemon = true } }

        /**
         * 请求体上限（8 MB）。
         *
         * 8 MB 足够装下最坏情况下的整机快照（课表 + 考试 + 待办），
         * 而未鉴权的对端没法用一个头就把手机内存吃光。
         */
        const val MAX_BODY_BYTES = 8L * 1024 * 1024

        /**
         * CORS 预检放行的请求头。
         *
         * 签名那四个与密文那个都是**自定义头** —— 预检里没列出来的头，
         * 浏览器会在真正发请求之前就拦掉。跨源时少列一个，
         * 表现就是「网页端连不上」，而同源时一切正常。
         */
        private val ALLOWED_HEADERS = listOf(
            "Authorization",
            "Content-Type",
            WebSyncApi.HEADER_DEVICE,
            WebSyncApi.HEADER_TS,
            WebSyncApi.HEADER_NONCE,
            WebSyncApi.HEADER_SIG,
            WebSyncApi.HEADER_ENC,
        )

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
