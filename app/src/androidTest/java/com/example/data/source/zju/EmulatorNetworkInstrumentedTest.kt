package com.example.data.source.zju

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertNotEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * 在真机 / 模拟器上验证 App 这一层的网络是不是真的通 —— 也就是 OkHttp 眼里的网络，
 * 不是 `adb shell` 里的。
 *
 * 为什么要单独测：本机那层透明代理会把模拟器经 NAT 出站的**明文 HTTP 全拦成
 * 502**，而浙大的课表接口恰好就是明文 `http://appservice.zju.edu.cn`。
 * 宿主机上跑 JVM 单测看不出这个问题（本机出站不走那条拦截），
 * 只有把请求真的从 App 进程发出去才暴露。
 *
 * 默认跳过，开跑：
 *   gradlew :app:connectedDebugAndroidTest -Pmiulist.liveNet=1
 * 前提是模拟器已经挂上宿主机的代理桥（见项目根的 start-emulator-proxy.bat）。
 */
@RunWith(AndroidJUnit4::class)
class EmulatorNetworkInstrumentedTest {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    @Test
    fun plaintextScheduleEndpointIsNotHijacked() {
        assumeTrue(
            "未开启真联网测试：加 -Pmiulist.liveNet=1",
            InstrumentationRegistry.getArguments().getString("miulist.instrumentedNet") == "1"
        )

        val response = client.newCall(
            Request.Builder().url("http://appservice.zju.edu.cn/").build()
        ).execute()
        val code = response.code
        response.close()

        // 302 是正常重定向到 CAS；502 说明明文请求被中间盒子吃掉了。
        assertNotEquals(
            "明文 HTTP 被拦成 502 —— 模拟器没挂上宿主机代理桥，见 start-emulator-proxy.bat",
            502,
            code
        )
    }

    /** CAS 走 HTTPS，代理桥会把它转给上游代理做 CONNECT。 */
    @Test
    fun casOverHttpsReachable() {
        assumeTrue(
            "未开启真联网测试：加 -Pmiulist.liveNet=1",
            InstrumentationRegistry.getArguments().getString("miulist.instrumentedNet") == "1"
        )

        val response = client.newCall(
            Request.Builder().url("https://zjuam.zju.edu.cn/cas/v2/getPubKey").build()
        ).execute()
        val body = response.use { it.body?.string().orEmpty() }
        assertNotEquals("CAS 公钥接口没拿到内容", 0, body.length)
        assert(body.contains("modulus")) { "公钥里没有 modulus，实际返回：$body" }
    }
}
