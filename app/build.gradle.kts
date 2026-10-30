import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy
import java.net.URI

// 真联网开关：命令行 -Pmiulist.liveNet=1 或环境变量 MIULIST_LIVE_NET=1。
// 关着的时候 JVM 单测与插桩测试里那几个「真的要出网」的用例直接跳过。
val liveNetFlag =
  (project.findProperty("miulist.liveNet") ?: System.getenv("MIULIST_LIVE_NET") ?: "").toString()

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

// ── 网页端资源 ────────────────────────────────────────────────
//
// 源码在仓库根的 web/，**不复制进 app/src/main/assets**：
// 复制会产生两份真相，改了 web/ 却忘了同步过去，App 里跑的是旧版网页。
// 这里改成构建期同步一份到 build/generated，再由 AGP 打进 assets。
//
// 之所以要打包进 APK 而不是让浏览器去电脑上取文件：
// **手机必须自己把网页端发出去** —— 局域网里别人扫一下手机上的码就能打开网页端，
// 电脑端不参与。（电脑上想独立跑网页端，用 `desktop/` 构建出的单文件 HTML，
// 接口仍然在手机上 —— 协议是同一套。）

/** 把 web/ 整份同步到构建目录。 */
abstract class SyncWebAssets : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val source: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun sync() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        // **整体放进 web/ 子目录**，不能把内容直接铺在输出根上 ——
        // AGP 把这个输出目录当成 assets 的根，铺平了 APK 里就成了
        // `assets/app.js`；而 App 那边读的是 `assets/web/...`，
        // 差一层目录的后果是「服务起来了，但网页 404」。
        val dst = File(out, "web")
        dst.mkdirs()
        val src = source.get().asFile
        src.copyRecursively(dst, overwrite = true)
        // **测试文件不进包**：crypto.test.mjs 是给开发时跑的自测，
        // 打进发布包只是白占体积，还让人以为线上要跑测试。
        dst.listFiles()?.forEach { f ->
            if (f.isFile && f.name.endsWith(".test.mjs")) f.delete()
        }
    }
}

val syncWebAssets = tasks.register<SyncWebAssets>("syncWebAssets") {
    group = "build"
    description = "把 web/ 同步到 assets"
    source.set(rootProject.layout.projectDirectory.dir("web"))
    outputDir.set(layout.buildDirectory.dir("generated/webAssets"))
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.scheduletodo.unmq"
    minSdk = 24
    targetSdk = 36
    versionCode = 13
    versionName = "1.1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    testInstrumentationRunnerArguments["miulist.instrumentedNet"] = liveNetFlag
  }

  // Do not remove or modify these signingConfigs. They are necessary for building, installing,
  // and updating Android apps in AI Studio.
  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
    // java.time (LocalDate/LocalDateTime) 在 minSdk 24 上需要 desugaring。
    // 校历与开课周次全部用 epochDay + java.time 手算，不用 Calendar 是因为
    // 跨夏令时「相差天数」会算错，那会直接变成周次判错。
    isCoreLibraryDesugaringEnabled = true
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions {
    unitTests {
      isIncludeAndroidResources = true
      all {
        // Robolectric 的临时目录名里带**测试方法名**，于是 Room 的库文件路径
        // = 临时目录 + 方法名 + 一串哈希 + 数据目录。方法名一长，
        // Windows 的 260 字符上限就到了 —— 表现是
        // `SQLiteCantOpenDatabaseException: unable to open database file`：
        // 主库文件能建，紧跟着要建的 `-wal` / `-shm` / `-journal` 建不出来。
        //
        // 而 `AppDatabase` 是**单例**：库文件路径由这个类里**第一个**跑到的用例
        // 决定，后面所有用例都跟着那条路径走 —— 于是「方法名长不长」变成了
        // 「整类能不能跑」的开关，而且顺序一变结果就变。
        //
        // 把测试 JVM 的临时目录指到一个短路径上，路径长度就与用例名无关了。
        // 目录建在用户目录下（每台机器各自一份），不在仓库里。
        val shortTmp = File(System.getProperty("user.home"), ".mt")
        shortTmp.mkdirs()
        it.systemProperty("java.io.tmpdir", shortTmp.absolutePath)
      }
    }
  }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  coreLibraryDesugaring(libs.desugar.jdk.libs)
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  // implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  // implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  implementation(libs.firebase.ai)
  // Uncomment to use Firestore:
  // implementation(libs.firebase.firestore)

  // Uncomment ALL FOUR of the following dependencies together to use Firebase Auth and Google
  // Sign-In via Credential Manager:
  // implementation(libs.firebase.auth)
  // implementation(libs.androidx.credentials)
  // implementation(libs.androidx.credentials.play.services)
  // implementation(libs.googleid)
  implementation(libs.firebase.appcheck.recaptcha)
  implementation(libs.firebase.appcheck.debug)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  implementation(libs.retrofit)
  implementation(libs.haze)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  // 迁移测试：没有它就只能靠手点验证 v3→v4 不丢数据
  testImplementation(libs.androidx.room.testing)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}

ksp {
  // Room schema 导出目录。exportSchema = true 之后 MigrationTestHelper
  // 需要 app/schemas/<version>.json 才能建旧版库再升级验证。
  arg("room.schemaLocation", "$projectDir/schemas")
  arg("room.incremental", "true")
}

// 打包资源之前必须先把 web/ 同步过去。
// 用 Variant API 的 generated source directory：任务依赖由 AGP 自动接上，
// 不用自己去猜该挂到哪个 merge*Assets 上（AGP 的任务名会变）。
androidComponents {
  onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(syncWebAssets) { it.outputDir }
  }
}

// 让 JVM 单测里的 OkHttp 真的能出网 —— 调试「爬课表 / 拉校历」全靠这个。
//
// 为什么必须显式写一遍：init.d 里设的代理只作用于 Gradle daemon 自己的 JVM，
// 而 test 是在 fork 出来的 worker JVM 里跑的，两边不共享 systemProperty。
// Java 默认 ProxySelector 读的正是 http(s).proxyHost/Port，OkHttp 不开自定义
// proxy 时走的就是它，所以这里一设，测试里的真实请求就能连上教务网。
//
// 端口从环境变量取，与 ~/.gradle/init.d/proxy.gradle 同源；不写死是因为
// 本地代理端口会在会话之间漂移。没配代理的机器上直接跳过，不影响离线测试。
//
// **为什么不是 doFirst**：doFirst 的闭包捕获了这段脚本里的引用（proxyUrl 等），
// 而 `org.gradle.configuration-cache=true` 不允许序列化脚本对象引用 ——
// 于是 `gradlew test` 会直接失败在「配置缓存」这一步，而不是跑测试。
// systemProperty 本来就是任务的普通属性，配置期设好即可，语义完全一样。
tasks.withType<Test>().configureEach {
  systemProperty("miulist.liveNet", liveNetFlag)
  val proxyUrl = System.getenv("https_proxy")
    ?: System.getenv("HTTPS_PROXY")
    ?: System.getenv("http_proxy")
    ?: System.getenv("HTTP_PROXY")
  val uri = proxyUrl?.let { runCatching { URI(it) }.getOrNull() }
  if (uri != null && uri.host != null && uri.port > 0) {
    systemProperty("http.proxyHost", uri.host)
    systemProperty("http.proxyPort", uri.port.toString())
    systemProperty("https.proxyHost", uri.host)
    systemProperty("https.proxyPort", uri.port.toString())
    // 本机回环别走代理，Robolectric / localhost mock 会挂在这上面。
    systemProperty("http.nonProxyHosts", "localhost|127.0.0.1|10.0.2.2")
    logger.lifecycle("[test-proxy] ${name} -> ${uri.host}:${uri.port}")
  }
}
