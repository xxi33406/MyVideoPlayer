import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// 签名配置从根目录 keystore.properties 读（不进 git，见 .gitignore）。
// 属性：storeFile / storePassword / keyAlias / keyPassword。
// 文件缺失时 release 走未签名构建，CI 或别人拉代码也能编过。
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasSigningConfig = keystoreProperties.containsKey("storeFile")

android {
    namespace = "com.example.myvideoplayer"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.myvideoplayer"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // R8 开启：全量代码收缩 + 混淆。本项目此前 APK 72MB 的主因就是
            // material-icons-extended 未被收缩（已换 core，见 ui/icons/AppIcons.kt），
            // 开 R8 后未引用的图标类与库代码会被剪掉，release 体积进一步下降。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasSigningConfig) {
                signingConfig = signingConfigs.create("release") {
                    storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                    storePassword = keystoreProperties.getProperty("storePassword")
                    keyAlias = keystoreProperties.getProperty("keyAlias")
                    keyPassword = keystoreProperties.getProperty("keyPassword")
                }
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
    ksp {
        // 导出 Room schema 到 app/schemas/，为后续迁移留下可核对的基准 JSON
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Media3
    implementation(libs.media3.exoplayer)
    // HLS：FileScanner 会把 .m3u8 收进库，但 DefaultMediaSourceFactory 在缺少本模块时
    // 会抛 "No suitable media source factory found for content type: 2"（2 = HLS），
    // 结果是 m3u8 一定播不了。补上这个模块才与扫描器的扩展名清单自洽。
    implementation(libs.media3.exoplayer.hls)
    // Compose 原生视频画面（PlayerSurface）：替代 AndroidView 包装的 PlayerView
    implementation(libs.media3.ui.compose)
    // MediaSession：把播放器的所有权交给前台 Service，切后台能继续播、
    // 锁屏和通知栏可控。挂在 ViewModel 上的播放器一旦离开播放页就没了。
    implementation(libs.media3.session)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // DocumentFile (SAF)
    implementation(libs.androidx.documentfile)

    // ViewModel Compose
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // repeatOnLifecycle + LocalLifecycleOwner（后者从 ui 包迁移到这里）
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Material Icons Extended
    // 只用 material-icons-core：extended 的 AAR 有 35.7MB，本项目只用到 17 个图标，
    // 缺的 12 个在 ui/icons/AppIcons.kt 里用等效路径数据补上了
    implementation(libs.androidx.compose.material.icons.core)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}