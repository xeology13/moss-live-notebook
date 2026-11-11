plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 不内置任何默认 key。想给自用的调试包预注入 key 时，构建时显式传 Gradle 属性：
//   ~/tools/gradle-8.11.1/bin/gradle assembleDebug -PmossInjectKey=sk-xxx
// 只认 -P 参数，不读环境变量 —— 免得机器上恰好有一份 .env 就被自动打进包。
// 正式包（release）永远注入空串，key 只能由用户在 App 设置页填写。
val mossKey = (findProperty("mossInjectKey") as String?).orEmpty()
if (mossKey.isNotBlank()) {
    logger.lifecycle("[MOSS] debug 包将内嵌 -PmossInjectKey 传入的 key（长度 ${mossKey.length}）")
} else {
    logger.lifecycle("[MOSS] 未通过 -PmossInjectKey 提供 key：APK 内不嵌 key，装机后在设置页填写")
}

android {
    namespace = "net.mosslive.notebook"
    compileSdk = 34
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "net.mosslive.notebook"
        minSdk = 26
        targetSdk = 34
        versionCode = 15
        versionName = "0.2.13"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    /**
     * 签名：正式包也用 debug keystore 签，这样能直接覆盖升级手机上已装的 debug 版本，
     * 不会因为签名不一致要求卸载（卸载会丢笔记）。keystore 不入库。
     */
    signingConfigs {
        create("local") {
            val ks = File(
                System.getenv("MOSS_KS_PATH")
                    ?: "${System.getProperty("user.home")}/.android/debug.keystore"
            )
            if (ks.isFile) {
                storeFile = ks
                storePassword = System.getenv("MOSS_KS_PASS") ?: "android"
                keyAlias = System.getenv("MOSS_KS_ALIAS") ?: "androiddebugkey"
                keyPassword = System.getenv("MOSS_KS_KEYPASS") ?: "android"
            }
        }
    }

    buildTypes {
        debug {
            // 调试包嵌一次性 key，方便直接装机；正式包不嵌。
            buildConfigField("String", "MOSS_API_KEY", "\"$mossKey\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 安全：正式包不内嵌任何 API key，key 只存在设置页写入的私有存储里，
            // 这样 APK 流出也拿不到账号额度。
            buildConfigField("String", "MOSS_API_KEY", "\"\"")
            signingConfig = signingConfigs.getByName("local")
        }
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
