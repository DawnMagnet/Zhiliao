package com.shatyuka.zhiliao

import com.shatyuka.zhiliao.hooks.AnswerAd
import com.shatyuka.zhiliao.hooks.AnswerListAd
import com.shatyuka.zhiliao.hooks.AntiTracking
import com.shatyuka.zhiliao.hooks.Article
import com.shatyuka.zhiliao.hooks.AutoRefresh
import com.shatyuka.zhiliao.hooks.ColorMode
import com.shatyuka.zhiliao.hooks.CommentAd
import com.shatyuka.zhiliao.hooks.CustomFilter
import com.shatyuka.zhiliao.hooks.ExternLink
import com.shatyuka.zhiliao.hooks.FeedAd
import com.shatyuka.zhiliao.hooks.FeedFloatAd
import com.shatyuka.zhiliao.hooks.FeedTopHotBanner
import com.shatyuka.zhiliao.hooks.FollowButton
import com.shatyuka.zhiliao.hooks.FullScreen
import com.shatyuka.zhiliao.hooks.HeadZoneBanner
import com.shatyuka.zhiliao.hooks.HotBanner
import com.shatyuka.zhiliao.hooks.IHook
import com.shatyuka.zhiliao.hooks.LaunchAd
import com.shatyuka.zhiliao.hooks.MineHybridView
import com.shatyuka.zhiliao.hooks.NavButton
import com.shatyuka.zhiliao.hooks.NavRes
import com.shatyuka.zhiliao.hooks.NextAnswer
import com.shatyuka.zhiliao.hooks.RedDot
import com.shatyuka.zhiliao.hooks.SearchAd
import com.shatyuka.zhiliao.hooks.ShareAd
import com.shatyuka.zhiliao.hooks.StatusBar
import com.shatyuka.zhiliao.hooks.Tag
import com.shatyuka.zhiliao.hooks.ThirdPartyLogin
import com.shatyuka.zhiliao.hooks.VIPBanner
import com.shatyuka.zhiliao.hooks.WebView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.net.URLClassLoader
import java.util.LinkedList

class HookTest {
    class PackageInfo {
        lateinit var name: String
        var versionCode: Int = 0
        lateinit var classLoader: ClassLoader
    }

    @Test
    fun candidateAssetIsSharedWithRuntime() {
        assertTrue(
            "单测没有载入 assets 里的候选名单，测试与运行时不同源",
            CandidateRegistry.getRevision() > 0
        )
    }

    /** @noinspection RedundantSuppression */
    @Suppress("DEPRECATION")
    private fun checkHook(hook: IHook) {
        for (packageInfo in packageInfos) {
            try {
                resetState(hook.javaClass)
                val androidInfo = android.content.pm.PackageInfo()
                androidInfo.versionCode = packageInfo.versionCode
                Helper.packageInfo = androidInfo
                Helper.versionCode = packageInfo.versionCode
                Helper.initSharedClasses(packageInfo.classLoader)
                hook.init(packageInfo.classLoader)
            } catch (e: ClassNotFoundException) {
                println("skip ${hook.getName()} on ${packageInfo.name}: ${e.message}")
            } catch (e: NoClassDefFoundError) {
                println("skip ${hook.getName()} on ${packageInfo.name}: ${e.message}")
            } catch (e: Throwable) {
                throw AssertionError("${hook.getName()}, ${packageInfo.name}", e)
            }
        }
    }

    @Test
    fun launchAdTest() {
        checkHook(LaunchAd())
    }

    /**
     * 联投开屏（超级首映 / 搜搜联动）：冷启动进首页后盖在信息流上的那支广告，和 sdk.launchad 完全两条链路。
     * 门槛取「新的开屏模块在不在」（LaunchApi），在就要求四条目标全部解析到——少一条真机上就是首页又飘出一支广告，
     * 或者浮层视频广告卡在等联投动画。
     */
    @Test
    fun launchAdCombineTest() {
        val names = arrayOf("checkData()", "isShowCombineAd()", "getSoSoCombineAdShow()", "existAdFeedSoSoData()")
        for (packageInfo in packageInfos) {
            val hasLaunchModule = hasClass(
                packageInfo.classLoader,
                "com.zhihu.android.launch.api.call.LaunchApi"
            )
            try {
                resetState(LaunchAd::class.java)
                Helper.initSharedClasses(packageInfo.classLoader)
                LaunchAd().init(packageInfo.classLoader)
            } catch (e: ClassNotFoundException) {
                println("skip 去启动页广告 on ${packageInfo.name}: ${e.message}")
                continue
            } catch (e: NoClassDefFoundError) {
                println("skip 去启动页广告 on ${packageInfo.name}: ${e.message}")
                continue
            } catch (e: Throwable) {
                throw AssertionError("去启动页广告, ${packageInfo.name}", e)
            }
            if (!hasLaunchModule) {
                continue
            }
            val targets = LaunchAd.combineTargets()
            for (i in names.indices) {
                assertNotNull(
                    "联投开屏的 ${names[i]} 没解析到（${packageInfo.name}）",
                    targets[i]
                )
            }
        }
    }

    @Test
    fun customFilterTest() {
        checkHook(CustomFilter())
    }

    @Test
    fun feedAdTest() {
        checkHook(FeedAd())
    }

    @Test
    fun feedFloatAdTest() {
        for (packageInfo in packageInfos) {
            try {
                resetState(FeedFloatAd::class.java)
                Helper.initSharedClasses(packageInfo.classLoader)
                FeedFloatAd().init(packageInfo.classLoader)
            } catch (e: ClassNotFoundException) {
                println("skip 去浮层广告 on ${packageInfo.name}: ${e.message}")
                continue
            } catch (e: NoClassDefFoundError) {
                println("skip 去浮层广告 on ${packageInfo.name}: ${e.message}")
                continue
            } catch (e: Throwable) {
                throw AssertionError("去浮层广告, ${packageInfo.name}", e)
            }
            val name = FeedFloatAd.initDataName()
            if (name != null) {
                assertEquals(
                    "浮层广告要挂在 initData 上，父类里还有个同签名的私有 isImageCached（" +
                        "${packageInfo.name}）",
                    "initData",
                    name
                )
            }
        }
    }

    @Test
    fun answerListAdTest() {
        checkHook(AnswerListAd())
    }

    @Test
    fun commentAdTest() {
        for (packageInfo in packageInfos) {
            try {
                resetState(CommentAd::class.java)
                Helper.initSharedClasses(packageInfo.classLoader)
                CommentAd().init(packageInfo.classLoader)
            } catch (e: ClassNotFoundException) {
                println("skip 去评论广告 on ${packageInfo.name}: ${e.message}")
                continue
            } catch (e: NoClassDefFoundError) {
                println("skip 去评论广告 on ${packageInfo.name}: ${e.message}")
                continue
            } catch (e: Throwable) {
                throw AssertionError("去评论广告, ${packageInfo.name}", e)
            }
            assertTrue(
                "评论广告模型没解析到（${packageInfo.name}）",
                CommentAd.hasModel()
            )
            assertTrue(
                "评论广告只认了模型、没有任何拦截路径（${packageInfo.name}）",
                CommentAd.canIntercept()
            )
            if (CommentAd.hasBindMethod()) {
                assertTrue(
                    "评论广告绑定方法必须是实例方法：hook 靠 thisObject 取 itemView",
                    CommentAd.bindCarriesHolder()
                )
            }
        }
    }

    @Test
    fun answerAdTest() {
        checkHook(AnswerAd())
    }

    @Test
    fun shareAdTest() {
        checkHook(ShareAd())
    }

    @Test
    fun nextAnswerTest() {
        checkHook(NextAnswer())
    }

    @Test
    fun redDotTest() {
        checkHook(RedDot())
    }

    @Test
    fun externLinkTest() {
        checkHook(ExternLink())
    }

    @Test
    fun vipBannerTest() {
        checkHook(VIPBanner())
    }

    @Test
    fun navButtonTest() {
        checkHook(NavButton())
    }

    @Test
    fun hotBannerTest() {
        checkHook(HotBanner())
    }

    @Test
    fun articleTest() {
        checkHook(Article())
    }

    @Test
    fun tagTest() {
        checkHook(Tag())
    }

    @Test
    fun searchAdTest() {
        checkHook(SearchAd())
    }

    @Test
    fun statusBarTest() {
        checkHook(StatusBar())
    }

    @Test
    fun thirdPartyLoginTest() {
        checkHook(ThirdPartyLogin())
    }

    @Test
    fun autoRefreshFreezesCacheOnEveryAuditJar() {
        for (packageInfo in packageInfos) {
            try {
                resetState(AutoRefresh::class.java)
                Helper.initSharedClasses(packageInfo.classLoader)
                AutoRefresh().init(packageInfo.classLoader)
            } catch (e: Throwable) {
                throw AssertionError("禁止自动刷新, ${packageInfo.name}", e)
            }
            assertTrue(
                "FeedLocalDataSource 的缓存写入没解析到（${packageInfo.name}）：冷启动屏幕不变，但下次冷启动会被缓存带跑",
                AutoRefresh.canFreezeCache()
            )
        }
    }

    @Test
    fun autoRefreshTest() {
        checkHook(AutoRefresh())
        assertTrue(
            "postRefreshSucceed 没解析到：冷启动只能退回直接拦截，首页会白屏",
            AutoRefresh.canSuppressNetworkResult()
        )
        assertTrue(
            "FeedLocalDataSource 的缓存写入没解析到：冷启动屏幕不变，但下次冷启动会被缓存带跑",
            AutoRefresh.canFreezeCache()
        )
    }

    @Test
    fun navResTest() {
        checkHook(NavRes())
    }

    @Test
    fun feedTopHotBannerTest() {
        checkHook(FeedTopHotBanner())
    }

    @Test
    fun headZoneBannerTest() {
        checkHook(HeadZoneBanner())
    }

    @Test
    fun mineHybridViewTest() {
        checkHook(MineHybridView())
    }

    @Test
    fun followButtonTest() {
        checkHook(FollowButton())
    }

    @Test
    fun fullScreen() {
        checkHook(FullScreen())
    }

    @Test
    fun colorModeTest() {
        checkHook(ColorMode())
    }

    @Test
    fun webViewTest() {
        checkHook(WebView())
    }

    @Test
    fun antiTrackingTest() {
        checkHook(AntiTracking())
    }

    companion object {
        val packageInfos: LinkedList<PackageInfo> = LinkedList()

        init {
            val path = testDir()
            println("Hook test samples: ${path.absolutePath}")
            val files = path.listFiles()
            if (files != null) {
                for (file in files) {
                    val fileName = file.name
                    val index1 = fileName.lastIndexOf(".")
                    val index2 = fileName.lastIndexOf(" ")
                    if (!"jar".equals(fileName.substring(index1 + 1), ignoreCase = true)) {
                        continue
                    }
                    try {
                        val packageInfo = PackageInfo()
                        packageInfo.name = fileName.substring(0, index2)
                        packageInfo.versionCode = fileName.substring(index2 + 1, index1).toInt()
                        packageInfo.classLoader = URLClassLoader(arrayOf(file.toURI().toURL()))
                        packageInfos.add(packageInfo)
                    } catch (e: Throwable) {
                        e.printStackTrace()
                    }
                }
            }
            loadCandidateAsset()
        }

        /**
         * 审计 JAR 目录：环境变量优先，其次项目根目录的 targetapp/jars（Gradle 单测的工作目录是 app/）。
         * 不猜旧目录——仓库里没有 11.x 的 JAR 时，这个类会整体跳过，兼容性解析交给每日巡检去跑。
         */
        private fun testDir(): File {
            val configured = System.getenv("ZHILIAO_TEST_DIR")
            if (configured != null && configured.isNotEmpty()) {
                return File(configured)
            }
            for (candidate in arrayOf(File("../targetapp/jars"), File("targetapp/jars"))) {
                if (candidate.isDirectory) {
                    return candidate
                }
            }
            return File("../targetapp/jars")
        }

        @BeforeClass
        @JvmStatic
        fun requireAuditJars() {
            Assume.assumeTrue(
                "没准备审计 JAR（tools/prepare-target.ps1 或 ZHILIAO_TEST_DIR），本次跳过 Hook 兼容性测试",
                packageInfos.isNotEmpty()
            )
        }

        /**
         * 单测和真机用同一份候选名单：直接读 app/src/main/assets 里的那个 JSON。
         * 少了这一步，测试里 registry 永远是空的，各 Hook 全走结构化兜底，
         * 等于把生产环境真正在用的那条解析链整个放过——最容易被忽略的测试盲区。
         */
        private fun loadCandidateAsset() {
            val candidates = arrayOf(
                File("src/main/assets/compatibility/candidates-v1.json"),
                File("app/src/main/assets/compatibility/candidates-v1.json"),
            )
            for (file in candidates) {
                if (!file.isFile) {
                    continue
                }
                try {
                    val json = String(
                        java.nio.file.Files.readAllBytes(file.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8
                    )
                    CandidateRegistry.install(json)
                    println(
                        "候选名单已载入: ${file.absolutePath} revision=${CandidateRegistry.getRevision()}"
                    )
                    return
                } catch (e: Throwable) {
                    println("候选名单载入失败: ${file.absolutePath} $e")
                }
            }
            println("没找到候选名单 JSON，测试将只覆盖结构化兜底路径")
        }

        @JvmStatic
        fun resetState(clazz: Class<out IHook>) {
            val fields = clazz.declaredFields
            for (field in fields) {
                if (Modifier.isStatic(field.modifiers)) {
                    val type = field.type
                    if (type == Class::class.java || type == Method::class.java || type == Field::class.java) {
                        field.setAccessible(true)
                        field.set(null, null)
                    }
                }
            }
        }

        private fun hasClass(classLoader: ClassLoader, name: String): Boolean {
            return try {
                Class.forName(name, false, classLoader)
                true
            } catch (ignore: Throwable) {
                false
            }
        }
    }
}
