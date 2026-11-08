package com.shatyuka.zhiliao.hooks;

import android.content.Context;
import android.content.SharedPreferences;

import com.shatyuka.zhiliao.Helper;
import com.shatyuka.zhiliao.TargetResolver;
import com.shatyuka.zhiliao.xposed.XC_MethodHook;
import com.shatyuka.zhiliao.xposed.XposedBridge;

import java.io.File;
import java.lang.reflect.Method;

public class LaunchAd implements IHook {
    static Class<?> AdNetworkManager;

    static Method isShowLaunchAd;
    static Method requestLaunchAd;
    static Method showLaunchAd;

    /**
     * 联投开屏（超级首映 / 搜搜联动）：冷启动进首页后把广告视图直接盖在信息流上，和 sdk.launchad 是两条链路。
     * 代码 11.10 就有，11.13 起才开始投放；旧基线同样会装上这两条，那两版没做真机回归。
     */
    static Method combineCheckData;
    static Method isShowCombineAd;
    static Method isSoSoCombineAd;
    static Method existAdSoSoData;

    private static final String COMBINE_WATCHER = "com.zhihu.android.app.ui.fragment.ad.combine.AdCombineWatcher";
    private static final String AD_LAUNCH_STATUS = "com.zhihu.android.ad.launch.AdLaunchStatus";
    private static final String AD_SOSO_SERVER = "com.zhihu.android.ad.launch.AdSoSoServer";

    /** 开屏广告的本地缓存库（LaunchAdRoomFactory#roomDbName），放的是预加载回来的广告与资源记录。 */
    private static final String CACHE_DB = "room_launch_ad";
    private static final String LOCAL_PREFS = "zhiliao_local";
    private static final String CACHE_CLEARED_KEY = "launch_ad_cache_cleared";

    @Override
    public String getName() {
        return "去启动页广告";
    }

    @Override
    public void init(final ClassLoader classLoader) throws Throwable {
        AdNetworkManager = Helper.findClass(classLoader, "com.zhihu.android.sdk.launchad.",
                (Class<?> clazz) -> {
                    for (java.lang.reflect.Field field : clazz.getDeclaredFields()) {
                        if (field.getType().getName().startsWith("okhttp3.")) {
                            return true;
                        }
                    }
                    return false;
                });
        if (AdNetworkManager == null) {
            throw new ClassNotFoundException("com.zhihu.android.sdk.launchad.AdNetworkManager");
        }
        Class<?> LaunchAdInterface = classLoader.loadClass("com.zhihu.android.ad.LaunchAdInterface");
        Class<?> LaunchAdHelper = Helper.findClass(classLoader, "com.zhihu.android.app.util.", 0, 32,
                (Class<?> clazz) -> {
                    return LaunchAdInterface.isAssignableFrom(clazz)
                            && clazz != LaunchAdInterface;
                });
        if (LaunchAdHelper == null) {
            throw new ClassNotFoundException("com.zhihu.android.app.util.LaunchAdHelper implements LaunchAdInterface");
        }
        isShowLaunchAd = LaunchAdHelper.getMethod("isShowLaunchAd");
        showLaunchAd = findShowLaunchAd(LaunchAdHelper);
        requestLaunchAd = TargetResolver.requireMethod(AdNetworkManager, false, 0,
                "requestLaunchAd(int,long,long,String)", method ->
                        method.getReturnType() == String.class
                                && java.util.Arrays.equals(method.getParameterTypes(), new Class<?>[]{int.class, long.class, long.class, String.class}));
        combineCheckData = resolveCombineCheckData(classLoader);
        isShowCombineAd = resolveStatusMethod(classLoader, AD_LAUNCH_STATUS, "isShowCombineAd");
        isSoSoCombineAd = resolveStatusMethod(classLoader, AD_LAUNCH_STATUS, "getSoSoCombineAdShow");
        existAdSoSoData = resolveStatusMethod(classLoader, AD_SOSO_SERVER, "existAdFeedSoSoData");
    }

    @Override
    public void hook() throws Throwable {
        XposedBridge.hookMethod(isShowLaunchAd, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (enabled())
                    param.setResult(false);
            }
        });
        XposedBridge.hookMethod(requestLaunchAd, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (enabled())
                    param.setResult("");
            }
        });
        // 唤醒开屏（推送 / 外链把知乎叫起来时弹的那支）走的是这条，它不看 isShowLaunchAd。
        if (showLaunchAd != null) {
            XposedBridge.hookMethod(showLaunchAd, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (enabled())
                        param.setResult(null);
                }
            });
        }
        // 联投开屏：进首页那一刻，AdCombineWatcher 先问「有没有素材」（超级首映状态位 / 搜搜联动数据），
        // 再问自己的 checkData()，全过了才路由到开屏页、把整屏视图盖在信息流上。
        if (combineCheckData != null) {
            XposedBridge.hookMethod(combineCheckData, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (enabled())
                        param.setResult(false);
                }
            });
        }
        // 三个素材判断一起答"没有"：单堵 checkData 的话，搜搜联动那个标志没人消费，每回一次首页都要再判一次
        // 并发一条错误埋点；浮层视频广告也会因为等"联投动画播完"把监听挂在那儿再也等不到。
        for (Method flag : new Method[]{isShowCombineAd, isSoSoCombineAd, existAdSoSoData}) {
            if (flag == null) {
                continue;
            }
            XposedBridge.hookMethod(flag, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (enabled())
                        param.setResult(false);
                }
            });
        }
        clearCachedLaunchAd();
    }

    /** 接口方法不会被混淆，但老版本可能有别的实现类，找不到就跳过这一条，别连累整个功能。 */
    private static Method findShowLaunchAd(Class<?> helper) {
        try {
            return helper.getMethod("showLaunchAd", String.class);
        } catch (NoSuchMethodException e) {
            XposedBridge.log("[Zhiliao][去启动页广告] 没找到 showLaunchAd(String)，唤醒开屏这条不装");
            return null;
        }
    }

    /**
     * 联投开屏的数据闸门。类上有 @Keep，名字没被混淆；方法名跟着类保留，签名再验一遍防同名顶替。
     * 11.13 之前没有这条链路，解析不到就是"本来就没有"，不打日志。
     */
    private static Method resolveCombineCheckData(ClassLoader classLoader) {
        try {
            Class<?> watcher = classLoader.loadClass(COMBINE_WATCHER);
            Method method = TargetResolver.findMethod(watcher, false, 0, candidate ->
                    "checkData".equals(candidate.getName())
                            && candidate.getParameterTypes().length == 0
                            && candidate.getReturnType() == boolean.class);
            if (method == null) {
                XposedBridge.log("[Zhiliao][去启动页广告] " + COMBINE_WATCHER + " 里没有 checkData()，联投开屏这条不装");
            }
            return method;
        } catch (ClassNotFoundException e) {
            return null;
        } catch (Throwable e) {
            XposedBridge.log("[Zhiliao][去启动页广告] 联投开屏监视器解析失败: " + e);
            return null;
        }
    }

    /** 公开的素材状态位 / 素材判断，方法名不会被混淆；类缺失（更老的版本）或方法缺失都只跳过这一条。 */
    private static Method resolveStatusMethod(ClassLoader classLoader, String className, String methodName) {
        try {
            return classLoader.loadClass(className).getMethod(methodName);
        } catch (ClassNotFoundException e) {
            return null;
        } catch (Throwable e) {
            XposedBridge.log("[Zhiliao][去启动页广告] " + className + "#" + methodName + " 解析失败: " + e);
            return null;
        }
    }

    /** 单测用：联投开屏四条目标解析成了什么（「类名#方法名」），没解析到为 null。 */
    public static String[] combineTargets() {
        return new String[]{
                describe(combineCheckData),
                describe(isShowCombineAd),
                describe(isSoSoCombineAd),
                describe(existAdSoSoData),
        };
    }

    private static String describe(Method method) {
        return method == null ? null : method.getDeclaringClass().getName() + "#" + method.getName();
    }

    private static boolean enabled() {
        return Helper.prefs.getBoolean("switch_mainswitch", false)
                && Helper.prefs.getBoolean("switch_launchad", true);
    }

    /**
     * 上面这些 Hook 只管这次启动的请求；装模块之前就已经落库的那支广告还在，得一起清掉。
     * 只在第一次清：清完之后请求已经被堵死，库里不会再长出新的开屏广告，没必要每次冷启动都去动
     * 知乎自己的数据库（知乎有多个进程，反复删容易撞上别人正开着的连接）。
     */
    private static void clearCachedLaunchAd() {
        if (!enabled()) {
            return;
        }
        try {
            Context context = Helper.context;
            if (context == null) {
                return;
            }
            SharedPreferences local = context.getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE);
            if (local.getBoolean(CACHE_CLEARED_KEY, false)) {
                return;
            }
            File database = context.getDatabasePath(CACHE_DB);
            if (database == null || !database.exists()) {
                local.edit().putBoolean(CACHE_CLEARED_KEY, true).apply();
                return;
            }
            boolean deleted = context.deleteDatabase(CACHE_DB);
            local.edit().putBoolean(CACHE_CLEARED_KEY, deleted).apply();
            XposedBridge.log(deleted
                    ? "[Zhiliao][去启动页广告] 已清空开屏缓存 " + CACHE_DB
                    : "[Zhiliao][去启动页广告] 清空开屏缓存失败，下次启动再试 " + CACHE_DB);
        } catch (Throwable e) {
            XposedBridge.log("[Zhiliao][去启动页广告] 清空开屏缓存异常: " + e);
        }
    }
}
