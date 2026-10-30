package com.example.data.sync.web

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 局域网发现：「手机上的 MiuList 在哪个地址」。
 *
 * ## 为什么需要它
 *
 * 网页端跑在电脑上时（`file://` 打开的单文件 HTML，或旧启动器的 `127.0.0.1`），
 * 而接口在手机那边 —— 两端**不同源**，网页端必须知道手机的地址才能发请求。
 *
 * 而手机的 HTTP 服务是**固定端口**：首选 `38780`，被占用就往后顺延到 `38784`
 * （`WebSyncService.PORT_CANDIDATES`）。
 * 所以「按固定端口去找」这条路**现在是走得通的** ——
 * 浏览器发不了 UDP 广播，但能**扫网段**（`web/discovery.js`）。
 *
 * 这个 UDP 探测口留给**旧启动器**（`tools/pc-web/launcher.py`）：
 * 它认得这个协议，能广播、也能收应答，于是不用扫网段就能直接拿到手机地址。
 * 应答里带的是**实际**那个 HTTP 端口 —— `38780` 被占用时可能是 `38781`–`38784` 里的某一个。
 *
 * - 探测端口固定：[ServiceDiscovery.PORT]（`38777`）—— 电脑端要能预先知道往哪儿发；
 * - HTTP 端口固定候选 `38780`–`38784` —— 由应答告诉电脑端实际用的是哪一个。
 *
 * ## 这会不会泄露什么
 *
 * 会泄露一件事：**这台设备上跑着 MiuList，以及它的 HTTP 端口号**。
 *
 * 这是可接受的，因为那个 HTTP 服务本来就在局域网里可访问（任何人都能连上，
 * 只是连不上数据 —— 数据要配对、要签名）。而保密的是「内容」，
 * 不是「地址」：请求体是密文，配对密钥只在两端内存里，线上只有公钥。
 *
 * 换句话说：**知道地址不等于能读数据。** 真要收紧，可以给应答加上
 * 「只回给探测包里带对握手语义的地址」，但那只会让电脑端更难用，
 * 换不来实质性的安全提升。
 *
 * ## 为什么用 UDP 而不是 HTTP
 *
 * HTTP 广播不成立：TCP 握手对广播地址不work，而且局域网里的其他设备
 * 也会收到 —— 它们得回一个「不是我」的应答，噪声比信号还大。
 * UDP 广播天生就是「喊一嗓子，谁是 MiuList 谁回话」。
 */
object ServiceDiscovery {

    /** 探测端口（UDP）。**固定值** —— 电脑端要能预先知道往哪儿发包。 */
    const val PORT = 38777

    /** 探测包的内容。带版本号，将来改协议时老电脑端能认出来。 */
    const val PROBE = "MIULIST-DISCOVER/1"

    /**
     * 这个包是不是探测包。
     *
     * **只认完全相等的内容** —— 局域网里别的程序也会往广播端口发包，
     * 认得宽松一点就会去给不相干的请求回一堆信息。
     */
    fun isProbe(data: ByteArray, length: Int): Boolean {
        if (length <= 0 || length > data.size) return false
        val text = String(data, 0, length, Charsets.UTF_8).trim()
        return text == PROBE
    }

    /**
     * 应答内容。
     *
     * 只放「找到它需要的东西」：HTTP 端口、设备名、App 版本。
     * **不放任何密钥、快照或待办**。
     */
    fun replyPayload(httpPort: Int, deviceName: String, appVersion: String): String = buildString {
        append("""{"app":"MiuList","protocol":3""")
        // 端口是**数字**，不是字符串 —— 后面不能再补引号。
        // 多补一个引号，JSON 就成了 `{"port":41234"`，电脑端解析失败，
        // 症状是「手机明明开着服务，电脑端就是找不到它」。
        append(""","port":""").append(httpPort)
        append(""","deviceName":"""").append(LanHttpServer.escapeJson(deviceName)).append('"')
        append(""","appVersion":"""").append(LanHttpServer.escapeJson(appVersion)).append('"')
        append("}")
    }
}

/**
 * 探测口的应答者。跟着局域网服务一起开关。
 *
 * 单独抽出来是为了让 [WebSyncService] 只管「开/关」，
 * 而 socket 的那些边角（超时、端口被占、收到别的包）都收在这里。
 */
class DiscoveryResponder(
    /** 取当前的 HTTP 端口。应答时才会调 —— 那时服务已经起来了。 */
    private val httpPortProvider: () -> Int,
    private val deviceName: () -> String,
    private val appVersion: () -> String,
) {
    private val running = AtomicBoolean(false)
    private var socket: DatagramSocket? = null

    /** 起探测口。返回是否成功（端口被占时返回 false，不影响局域网服务本身）。 */
    fun start(): Boolean {
        if (running.get()) return true
        val sock = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(ServiceDiscovery.PORT))
                broadcast = true
                // 收包要有超时，否则 stop() 时线程醒不过来。
                soTimeout = 500
            }
        } catch (_: Exception) {
            // 端口被占（别的 MiuList 实例、或别的程序）→ 发现不可用，
            // 但局域网同步服务照旧工作，用户也可以手填地址。
            return false
        }
        socket = sock
        running.set(true)
        Thread({ loop(sock) }, "miulist-discovery").apply { isDaemon = true }.start()
        return true
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        try { socket?.close() } catch (_: Exception) { }
        socket = null
    }

    private fun loop(sock: DatagramSocket) {
        val buffer = ByteArray(512)
        while (running.get()) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                sock.receive(packet)
            } catch (_: SocketTimeoutException) {
                // 没等到包 —— 正常，继续等。
                continue
            } catch (_: Exception) {
                // stop() 关掉 socket 时也会走到这里。
                if (running.get()) continue else break
            }
            if (!ServiceDiscovery.isProbe(buffer, packet.length)) continue
            try {
                val reply = ServiceDiscovery.replyPayload(
                    httpPortProvider(),
                    deviceName(),
                    appVersion(),
                ).toByteArray(Charsets.UTF_8)
                sock.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
            } catch (_: Exception) {
                // 回不回这一包不影响服务 —— 电脑端会继续广播。
            }
        }
    }
}
