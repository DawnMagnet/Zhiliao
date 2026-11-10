plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val appVerName = providers.gradleProperty("appVerName").get()
val appVerCode = providers.gradleProperty("appVerCode").get().toInt()
val releaseStoreFileProp = providers.gradleProperty("releaseStoreFile")
val skipNativeBuild = providers.gradleProperty("skipNativeBuild").isPresent

kotlin {
    jvmToolchain(17)
}

android {
    namespace = "com.shatyuka.zhiliao"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.shatyuka.zhiliao"
        minSdk = 26
        targetSdk = 36
        ndkVersion = "30.0.16248370"
        versionCode = appVerCode
        versionName = appVerName
        resValue("string", "app_version", appVerName)
        if (!skipNativeBuild) {
            externalNativeBuild {
                cmake {
                    abiFilters += listOf("arm64-v8a", "armeabi-v7a")
                    arguments += "-DANDROID_STL=none"
                    val nativeFlags = listOf(
                        "-Qunused-arguments",
                        "-Wno-gnu-string-literal-operator-template",
                        "-fno-rtti",
                        "-fvisibility=hidden",
                        "-fvisibility-inlines-hidden",
                        "-fno-exceptions",
                        "-fno-stack-protector",
                        "-fomit-frame-pointer",
                        "-Wno-builtin-macro-redefined",
                        "-Wno-unused-value",
                        "-D__FILE__=__FILE_NAME__",
                    )
                    cppFlags += listOf("-std=c++20") + nativeFlags
                    cFlags += listOf("-std=c18") + nativeFlags
                }
            }
        }
    }

    if (releaseStoreFileProp.isPresent) {
        signingConfigs {
            create("config") {
                storeFile = file(releaseStoreFileProp.get())
                storePassword = providers.gradleProperty("releaseStorePassword").get()
                keyAlias = providers.gradleProperty("releaseKeyAlias").get()
                keyPassword = providers.gradleProperty("releaseKeyPassword").get()
            }
        }
    }

    buildTypes {
        configureEach {
            if (releaseStoreFileProp.isPresent) {
                signingConfig = signingConfigs.getByName("config")
            } else {
                logger.lifecycle("using debug sign")
                signingConfig = signingConfigs.getByName("debug")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (!skipNativeBuild) {
                externalNativeBuild {
                    cmake {
                        val releaseFlags = listOf(
                            "-flto",
                            "-ffunction-sections",
                            "-fdata-sections",
                            "-Wl,--gc-sections",
                            "-fno-unwind-tables",
                            "-fno-asynchronous-unwind-tables",
                            "-Wl,--exclude-libs,ALL",
                        )
                        cppFlags += releaseFlags
                        cFlags += releaseFlags
                        arguments += listOf(
                            "-DCMAKE_BUILD_TYPE=Release",
                            "-DCMAKE_CXX_FLAGS_RELEASE=-Oz -DNDEBUG",
                            "-DCMAKE_C_FLAGS_RELEASE=-Oz -DNDEBUG",
                        )
                    }
                }
            }
        }
        debug {
            if (!skipNativeBuild) {
                externalNativeBuild {
                    cmake {
                        arguments += listOf(
                            "-DCMAKE_CXX_FLAGS_DEBUG=-Og",
                            "-DCMAKE_C_FLAGS_DEBUG=-Og",
                        )
                    }
                }
            }
        }
    }

    // CI 巡检只在 JVM 里跑兼容性单测，不需要 NDK：-PskipNativeBuild 时干脆不配 CMake，
    // 省掉整条 native 依赖链（NDK/CMake 下载、Dobby 子模块）
    if (!skipNativeBuild) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1+"
            }
        }
    }

    buildFeatures {
        prefab = !skipNativeBuild
        resValues = true
        compose = true
    }

    packaging {
        jniLibs {
            excludes += setOf("META-INF/**", "kotlin/**", "google/**")
            useLegacyPackaging = false
        }
        resources {
            // META-INF/xposed/** declares the module to the framework, only strip dependency metadata
            excludes += setOf(
                "META-INF/*.version",
                "META-INF/*.kotlin_module",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "kotlin/**",
                "google/**",
                "**.bin",
                "META-INF/{AL2.0,LGPL2.1}",
                "META-INF/INDEX.LIST",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.outputs.forEach { output ->
            val version = output.versionName.orNull ?: appVerName
            output.outputFileName.set("Zhiliao_$version.apk")
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:-options")
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    if (!skipNativeBuild) {
        implementation(libs.cxx)
    }
    testImplementation(libs.junit)
    // android.jar 里的 org.json 是抛 Stub! 的桩，JVM 单测用真实现
    testImplementation(libs.json)
    compileOnly(libs.libxposed.api)
    testImplementation(libs.libxposed.api)
    // 独立设置 App。界面层专用的依赖都收在这里。
    implementation(libs.libxposed.service)
    // 反追踪：服务端把二维码内容缩成 s.zhihu.com 短链，只能本地编码
    implementation(libs.zxing.core)
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.miuix.android)
    // DexKit：候选名单和结构特征都找不到时的最后一级兜底（原生扫描 dex，按字符串/签名定位）
    implementation(libs.dexkit)
}
