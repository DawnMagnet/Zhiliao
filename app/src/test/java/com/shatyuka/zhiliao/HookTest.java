package com.shatyuka.zhiliao;

import com.shatyuka.zhiliao.hooks.AnswerAd;
import com.shatyuka.zhiliao.hooks.AnswerListAd;
import com.shatyuka.zhiliao.hooks.Article;
import com.shatyuka.zhiliao.hooks.AutoRefresh;
import com.shatyuka.zhiliao.hooks.ColorMode;
import com.shatyuka.zhiliao.hooks.CommentAd;
import com.shatyuka.zhiliao.hooks.CustomFilter;
import com.shatyuka.zhiliao.hooks.ExternLink;
import com.shatyuka.zhiliao.hooks.FeedAd;
import com.shatyuka.zhiliao.hooks.FeedFloatAd;
import com.shatyuka.zhiliao.hooks.FeedTopHotBanner;
import com.shatyuka.zhiliao.hooks.FollowButton;
import com.shatyuka.zhiliao.hooks.FullScreen;
import com.shatyuka.zhiliao.hooks.HeadZoneBanner;
import com.shatyuka.zhiliao.hooks.HotBanner;
import com.shatyuka.zhiliao.hooks.IHook;
import com.shatyuka.zhiliao.hooks.LaunchAd;
import com.shatyuka.zhiliao.hooks.MineHybridView;
import com.shatyuka.zhiliao.hooks.NavButton;
import com.shatyuka.zhiliao.hooks.NavRes;
import com.shatyuka.zhiliao.hooks.NextAnswer;
import com.shatyuka.zhiliao.hooks.RedDot;
import com.shatyuka.zhiliao.hooks.SearchAd;
import com.shatyuka.zhiliao.hooks.ShareAd;
import com.shatyuka.zhiliao.hooks.StatusBar;
import com.shatyuka.zhiliao.hooks.Tag;
import com.shatyuka.zhiliao.hooks.ThirdPartyLogin;
import com.shatyuka.zhiliao.hooks.VIPBanner;
import com.shatyuka.zhiliao.hooks.WebView;
import com.shatyuka.zhiliao.hooks.AntiTracking;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.LinkedList;

public class HookTest {
    static class PackageInfo {
        String name;
        int versionCode;
        ClassLoader classLoader;
    }

    static LinkedList<PackageInfo> packageInfos = new LinkedList<>();

    static {
        File path = testDir();
        System.out.println("Hook test samples: " + path.getAbsolutePath());
        File[] files = path.listFiles();
        if (files != null) {
            for (File file : files) {
                String fileName = file.getName();
                int index1 = fileName.lastIndexOf(".");
                int index2 = fileName.lastIndexOf(" ");
                if (!"jar".equalsIgnoreCase(fileName.substring(index1 + 1)))
                    continue;
                try {
                    PackageInfo packageInfo = new PackageInfo();
                    packageInfo.name = fileName.substring(0, index2);
                    packageInfo.versionCode = Integer.parseInt(fileName.substring(index2 + 1, index1));
                    packageInfo.classLoader = new URLClassLoader(new URL[]{file.toURI().toURL()});
                    packageInfos.add(packageInfo);
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }
        }
        loadCandidateAsset();
    }

    /**
     * 审计 JAR 目录：环境变量优先，其次项目根目录的 targetapp/jars（Gradle 单测的工作目录是 app/）。
     * 不猜旧目录——仓库里没有 11.x 的 JAR 时，这个类会整体跳过，兼容性解析交给每日巡检去跑。
     */
    private static File testDir() {
        String configured = System.getenv("ZHILIAO_TEST_DIR");
        if (configured != null && !configured.isEmpty()) {
            return new File(configured);
        }
        for (File candidate : new File[]{new File("../targetapp/jars"), new File("targetapp/jars")}) {
            if (candidate.isDirectory()) {
                return candidate;
            }
        }
        return new File("../targetapp/jars");
    }

    @BeforeClass
    public static void requireAuditJars() {
        Assume.assumeTrue("没准备审计 JAR（tools/prepare-target.ps1 或 ZHILIAO_TEST_DIR），本次跳过 Hook 兼容性测试",
                !packageInfos.isEmpty());
    }

    /**
     * 单测和真机用同一份候选名单：直接读 app/src/main/assets 里的那个 JSON。
     * 少了这一步，测试里 registry 永远是空的，各 Hook 全走结构化兜底，
     * 等于把生产环境真正在用的那条解析链整个放过——最容易被忽略的测试盲区。
     */
    private static void loadCandidateAsset() {
        File[] candidates = {
                new File("src/main/assets/compatibility/candidates-v1.json"),
                new File("app/src/main/assets/compatibility/candidates-v1.json"),
        };
        for (File file : candidates) {
            if (!file.isFile()) {
                continue;
            }
            try {
                String json = new String(java.nio.file.Files.readAllBytes(file.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8);
                CandidateRegistry.install(json);
                System.out.println("候选名单已载入: " + file.getAbsolutePath()
                        + " revision=" + CandidateRegistry.getRevision());
                return;
            } catch (Throwable e) {
                System.out.println("候选名单载入失败: " + file.getAbsolutePath() + " " + e);
            }
        }
        System.out.println("没找到候选名单 JSON，测试将只覆盖结构化兜底路径");
    }

    @Test
    public void candidateAssetIsSharedWithRuntime() {
        org.junit.Assert.assertTrue("单测没有载入 assets 里的候选名单，测试与运行时不同源",
                CandidateRegistry.getRevision() > 0);
    }

    public static void resetState(Class<? extends IHook> clazz) throws IllegalAccessException {
        Field[] fields = clazz.getDeclaredFields();
        for (Field field : fields) {
            if (Modifier.isStatic(field.getModifiers())) {
                Class<?> type = field.getType();
                if (type == Class.class || type == Method.class || type == Field.class) {
                    field.setAccessible(true);
                    field.set(null, null);
                }
            }
        }
    }

    /** @noinspection RedundantSuppression*/
    @SuppressWarnings("deprecation")
    void checkHook(IHook hook) {
        for (PackageInfo packageInfo : packageInfos) {
            try {
                resetState(hook.getClass());
                Helper.packageInfo = new android.content.pm.PackageInfo();
                Helper.packageInfo.versionCode = packageInfo.versionCode;
                Helper.versionCode = packageInfo.versionCode;
                Helper.initSharedClasses(packageInfo.classLoader);
                hook.init(packageInfo.classLoader);
            } catch (ClassNotFoundException | NoClassDefFoundError e) {
                System.out.println("skip " + hook.getName() + " on " + packageInfo.name + ": " + e.getMessage());
            } catch (Throwable e) {
                throw new AssertionError(hook.getName() + ", " + packageInfo.name, e);
            }
        }
    }

    @Test
    public void launchAdTest() {
        checkHook(new LaunchAd());
    }

    /**
     * 联投开屏（超级首映 / 搜搜联动）：冷启动进首页后盖在信息流上的那支广告，和 sdk.launchad 完全两条链路。
     * 门槛取「新的开屏模块在不在」（LaunchApi），在就要求四条目标全部解析到——少一条真机上就是首页又飘出一支广告，
     * 或者浮层视频广告卡在等联投动画。
     */
    @Test
    public void launchAdCombineTest() {
        String[] names = {"checkData()", "isShowCombineAd()", "getSoSoCombineAdShow()", "existAdFeedSoSoData()"};
        for (PackageInfo packageInfo : packageInfos) {
            boolean hasLaunchModule = hasClass(packageInfo.classLoader,
                    "com.zhihu.android.launch.api.call.LaunchApi");
            try {
                resetState(LaunchAd.class);
                Helper.initSharedClasses(packageInfo.classLoader);
                new LaunchAd().init(packageInfo.classLoader);
            } catch (ClassNotFoundException | NoClassDefFoundError e) {
                System.out.println("skip 去启动页广告 on " + packageInfo.name + ": " + e.getMessage());
                continue;
            } catch (Throwable e) {
                throw new AssertionError("去启动页广告, " + packageInfo.name, e);
            }
            if (!hasLaunchModule) {
                continue;
            }
            String[] targets = LaunchAd.combineTargets();
            for (int i = 0; i < names.length; i++) {
                org.junit.Assert.assertNotNull("联投开屏的 " + names[i] + " 没解析到（" + packageInfo.name + "）", targets[i]);
            }
        }
    }

    private static boolean hasClass(ClassLoader classLoader, String name) {
        try {
            Class.forName(name, false, classLoader);
            return true;
        } catch (Throwable ignore) {
            return false;
        }
    }

    @Test
    public void customFilterTest() {
        checkHook(new CustomFilter());
    }

    @Test
    public void feedAdTest() {
        checkHook(new FeedAd());
    }

    @Test
    public void feedFloatAdTest() {
        for (PackageInfo packageInfo : packageInfos) {
            try {
                resetState(FeedFloatAd.class);
                Helper.initSharedClasses(packageInfo.classLoader);
                new FeedFloatAd().init(packageInfo.classLoader);
            } catch (ClassNotFoundException | NoClassDefFoundError e) {
                System.out.println("skip 去浮层广告 on " + packageInfo.name + ": " + e.getMessage());
                continue;
            } catch (Throwable e) {
                throw new AssertionError("去浮层广告, " + packageInfo.name, e);
            }
            String name = FeedFloatAd.initDataName();
            if (name != null) {
                org.junit.Assert.assertEquals("浮层广告要挂在 initData 上，父类里还有个同签名的私有 isImageCached（"
                        + packageInfo.name + "）", "initData", name);
            }
        }
    }

    @Test
    public void answerListAdTest() {
        checkHook(new AnswerListAd());
    }

    @Test
    public void commentAdTest() {
        for (PackageInfo packageInfo : packageInfos) {
            try {
                resetState(CommentAd.class);
                Helper.initSharedClasses(packageInfo.classLoader);
                new CommentAd().init(packageInfo.classLoader);
            } catch (ClassNotFoundException | NoClassDefFoundError e) {
                System.out.println("skip 去评论广告 on " + packageInfo.name + ": " + e.getMessage());
                continue;
            } catch (Throwable e) {
                throw new AssertionError("去评论广告, " + packageInfo.name, e);
            }
            org.junit.Assert.assertTrue("评论广告模型没解析到（" + packageInfo.name + "）",
                    CommentAd.hasModel());
            org.junit.Assert.assertTrue("评论广告只认了模型、没有任何拦截路径（" + packageInfo.name + "）",
                    CommentAd.canIntercept());
            if (CommentAd.hasBindMethod()) {
                org.junit.Assert.assertTrue("评论广告绑定方法必须是实例方法：hook 靠 thisObject 取 itemView",
                        CommentAd.bindCarriesHolder());
            }
        }
    }

    @Test
    public void answerAdTest() {
        checkHook(new AnswerAd());
    }

    @Test
    public void shareAdTest() {
        checkHook(new ShareAd());
    }

    @Test
    public void nextAnswerTest() {
        checkHook(new NextAnswer());
    }

    @Test
    public void redDotTest() {
        checkHook(new RedDot());
    }

    @Test
    public void externLinkTest() {
        checkHook(new ExternLink());
    }

    @Test
    public void vipBannerTest() {
        checkHook(new VIPBanner());
    }

    @Test
    public void navButtonTest() {
        checkHook(new NavButton());
    }

    @Test
    public void hotBannerTest() {
        checkHook(new HotBanner());
    }

    @Test
    public void articleTest() {
        checkHook(new Article());
    }

    @Test
    public void tagTest() {
        checkHook(new Tag());
    }

    @Test
    public void searchAdTest() {
        checkHook(new SearchAd());
    }

    @Test
    public void statusBarTest() {
        checkHook(new StatusBar());
    }

    @Test
    public void thirdPartyLoginTest() {
        checkHook(new ThirdPartyLogin());
    }

    @Test
    public void autoRefreshFreezesCacheOnEveryAuditJar() {
        for (PackageInfo packageInfo : packageInfos) {
            try {
                resetState(AutoRefresh.class);
                Helper.initSharedClasses(packageInfo.classLoader);
                new AutoRefresh().init(packageInfo.classLoader);
            } catch (Throwable e) {
                throw new AssertionError("禁止自动刷新, " + packageInfo.name, e);
            }
            org.junit.Assert.assertTrue("FeedLocalDataSource 的缓存写入没解析到（" + packageInfo.name
                            + "）：冷启动屏幕不变，但下次冷启动会被缓存带跑",
                    AutoRefresh.canFreezeCache());
        }
    }

    @Test
    public void autoRefreshTest() {
        checkHook(new AutoRefresh());
        org.junit.Assert.assertTrue("postRefreshSucceed 没解析到：冷启动只能退回直接拦截，首页会白屏",
                AutoRefresh.canSuppressNetworkResult());
        org.junit.Assert.assertTrue("FeedLocalDataSource 的缓存写入没解析到：冷启动屏幕不变，但下次冷启动会被缓存带跑",
                AutoRefresh.canFreezeCache());
    }

    @Test
    public void navResTest() {
        checkHook(new NavRes());
    }

    @Test
    public void feedTopHotBannerTest() {
        checkHook(new FeedTopHotBanner());
    }

    @Test
    public void headZoneBannerTest() {
        checkHook(new HeadZoneBanner());
    }

    @Test
    public void mineHybridViewTest() {
        checkHook(new MineHybridView());
    }

    @Test
    public void followButtonTest() {
        checkHook(new FollowButton());
    }

    @Test
    public void fullScreen() {
        checkHook(new FullScreen());
    }

    @Test
    public void colorModeTest() {
        checkHook(new ColorMode());
    }

    @Test
    public void webViewTest() {
        checkHook(new WebView());
    }

    @Test
    public void antiTrackingTest() {
        checkHook(new AntiTracking());
    }

}
