pluginManagement {
  repositories {
    google {
      content {
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
        includeGroupByRegex("androidx.*")
        // KSP 2.x is published to Maven Central only; without this exclusion Gradle
        // treats com.google.devtools.ksp as owned by google() and never falls back.
        excludeGroup("com.google.devtools.ksp")
      }
    }
    // repo1 是 Maven Central 的另一条域名，实测在本机代理下比
    // repo.maven.apache.org 稳，所以排在前面；central 本身留作兜底。
    maven { url = uri("https://repo1.maven.org/maven2/") }
    mavenCentral()
    gradlePluginPortal()
  }
}

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    // 本地兜底仓库，放最前面：它就是个目录，逐模块探测成本接近零，
    // 而放在后面的话 Gradle 会先去 repo1 撞上下面这个问题。
    //
    // 加它是因为两个 100MB+ 的 artifact 在本机会被代理从传输中途掐断，
    // 而 Gradle 既不重试也不续传，只会一直卡住（实测停在 51MB 不动）：
    //   · org.robolectric:nativeruntime-dist-compat:1.0.18        151 MB
    //   · org.robolectric:android-all-instrumented:...-i7         144 MB（Robolectric 运行时另取）
    // 两份（来自 repo1.maven.org）已按 Maven 布局落盘，让
    // :app:testDebugUnitTest 离线也能跑。换到网络正常的机器时删掉这一行即可。
    maven { url = uri("file:///D:/MiuList/local-m2") }
    google()
    maven { url = uri("https://repo1.maven.org/maven2/") }
    mavenCentral()
  }
}

rootProject.name = "MiuList"

include(":app")
