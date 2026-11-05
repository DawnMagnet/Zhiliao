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

    private static boolean enabled() {
        return Helper.prefs.getBoolean("switch_mainswitch", false)
                && Helper.prefs.getBoolean("switch_launchad", true);
    }

    /**
     * 上面三个 Hook 只管这次启动的请求；装模块之前就已经落库的那支广告还在，得一起清掉。
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
