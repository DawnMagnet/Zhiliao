package com.shatyuka.zhiliao.hooks

import android.content.Context
import android.view.View
import android.view.ViewGroup
import com.shatyuka.zhiliao.CandidateRegistry
import com.shatyuka.zhiliao.Helper
import com.shatyuka.zhiliao.TargetResolver
import com.shatyuka.zhiliao.xposed.XC_MethodHook
import com.shatyuka.zhiliao.xposed.XC_MethodHook.MethodHookParam
import com.shatyuka.zhiliao.xposed.XposedBridge
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Arrays

class CommentAd : IHook {
    override fun getName(): String {
        return FEATURE
    }

    override fun init(classLoader: ClassLoader) {
        try {
            ViewHolder = classLoader.loadClass("androidx.recyclerview.widget.RecyclerView" + '$' + "ViewHolder")
            itemView = ViewHolder!!.getField("itemView")
        } catch (ignored: Throwable) {
            ViewHolder = null
            itemView = null
        }

        CommentListAd = TargetResolver.findClassByQualifiedCandidates(
            classLoader,
            "commentAd.model",
        ) { clazz -> looksLikeCommentListAd(clazz) }
        if (CommentListAd == null) {
            CommentListAd = loadLegacyModel(classLoader)
        }
        if (CommentListAd == null) {
            return
        }

        if (Helper.MorphAdHelper != null) {
            val model = CommentListAd
            resolveCommentAdParam = TargetResolver.findMethod(Helper.MorphAdHelper, false, 0) { method ->
                Modifier.isStatic(method.modifiers) &&
                    method.returnType == Boolean::class.javaPrimitiveType &&
                    Arrays.equals(
                        method.parameterTypes,
                        arrayOf(Context::class.java, model, Boolean::class.java),
                    )
            }
        }

        if (resolveCommentAdParam == null && ViewHolder != null) {
            var holder = TargetResolver.findClassByQualifiedCandidates(
                classLoader,
                "commentAd.viewHolder",
            ) { clazz -> looksLikeAdViewHolder(clazz, ViewHolder) }
            if (holder == null) {
                holder = loadLegacyHolder(classLoader)
            }
            if (holder != null) {
                bindCommentAd = findBindMethod(holder)
            }
        }

        val bind = bindCommentAd
        XposedBridge.log(
            "[Zhiliao][自检] 去评论广告 模型=${CommentListAd!!.name}" +
                " 参数拦截=${resolveCommentAdParam != null}" +
                " 绑定=" + if (bind == null) {
                    "无"
                } else {
                    "${bind.declaringClass.name}#${bind.name}"
                },
        )
    }

    override fun hook() {
        if (resolveCommentAdParam != null) {
            XposedBridge.hookMethod(resolveCommentAdParam!!, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (isEnabled()) {
                        param.result = false
                    }
                }
            })
        }
        if (bindCommentAd != null && ViewHolder != null && itemView != null) {
            XposedBridge.hookMethod(bindCommentAd!!, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isEnabled() || param.thisObject == null || !ViewHolder!!.isInstance(param.thisObject)) {
                        return
                    }
                    val value = itemView!!.get(param.thisObject)
                    if (value !is View) {
                        return
                    }
                    value.visibility = View.GONE
                    val layoutParams = value.layoutParams
                    if (layoutParams != null) {
                        layoutParams.height = 0
                        value.layoutParams = layoutParams
                    }
                    param.result = null
                }
            })
        }
    }

    companion object {
        private const val FEATURE = "去评论广告"

        /** 广告卡片模型的基类：渠道间只换包名，顺序交给候选名单，名单不可用时退回两个已知包名。 */
        private val FALLBACK_AD_BASES = arrayOf(
            "com.zhihu.android.api.model.FeedAdvert",
            "com.zhihu.android.adbase.model.FeedAdvert",
        )
        private val LEGACY_MODELS = arrayOf(
            "com.zhihu.android.api.model.CommentListAd",
            "com.zhihu.android.adbase.model.CommentListAd",
        )
        private val LEGACY_HOLDERS = arrayOf(
            "com.zhihu.android.comment.holder.CommentDynamicAdViewHolderV70",
        )

        @JvmField
        var CommentListAd: Class<*>? = null

        @JvmField
        var ViewHolder: Class<*>? = null

        @JvmField
        var resolveCommentAdParam: Method? = null

        @JvmField
        var bindCommentAd: Method? = null

        @JvmField
        var itemView: Field? = null

        /**
         * 评论广告模型的形状：公开具体类，而且确实是广告卡片模型（FeedAdvert 的子类）。
         * 原来只验"公开且非抽象"，等于没验——名单里任何一个同名类都会被当成广告模型。
         */
        private fun looksLikeCommentListAd(clazz: Class<*>): Boolean {
            return looksLikeAdCardModel(clazz, advertBaseNames())
        }

        /** 基类名单作为参数传入，单测才能用假类验证"只认 FeedAdvert 的子类"这条规则。 */
        @JvmStatic
        fun looksLikeAdCardModel(clazz: Class<*>?, baseNames: Array<String>?): Boolean {
            try {
                val modifiers = clazz!!.modifiers
                if (!Modifier.isPublic(modifiers) || Modifier.isAbstract(modifiers)
                    || clazz.isInterface || clazz.isEnum
                ) {
                    return false
                }
                val classLoader = clazz.classLoader
                for (name in baseNames!!) {
                    val base = loadOptional(classLoader, name)
                    if (base != null && base !== clazz && base.isAssignableFrom(clazz)) {
                        return true
                    }
                }
                return false
            } catch (e: Throwable) {
                return false
            }
        }

        private fun advertBaseNames(): Array<String> {
            val fromRegistry = CandidateRegistry.candidates("feedAd.feedAdvert")
            if (fromRegistry != null && fromRegistry.isNotEmpty()) {
                return fromRegistry
            }
            return FALLBACK_AD_BASES
        }

        /** 广告 ViewHolder：RecyclerView.ViewHolder 的具体子类，且有一个只吃该模型的 void 绑定方法。 */
        @JvmStatic
        fun looksLikeAdViewHolder(clazz: Class<*>?, viewHolderBase: Class<*>?): Boolean {
            try {
                if (clazz == null || clazz === viewHolderBase || viewHolderBase == null
                    || !viewHolderBase.isAssignableFrom(clazz)
                    || Modifier.isAbstract(clazz.modifiers)
                ) {
                    return false
                }
                return findBindMethod(clazz) != null
            } catch (e: Throwable) {
                return false
            }
        }

        /**
         * 绑定方法：实例方法、返回 void、参数恰好是评论广告模型，并且唯一。
         *
         * 这里收实例方法是有原因的：知乎真正做绑定的是 V70 的 J1(CommentListAd)，它是实例方法，
         * hook 里又靠 thisObject 取 itemView。旧代码只收 static，两条要求互相矛盾，
         * 于是 11.10 的渠道包上这条 Hook 装了但一次都不会进来。
         */
        private fun findBindMethod(holder: Class<*>): Method? {
            var result: Method? = null
            for (method in holder.declaredMethods) {
                val modifiers = method.modifiers
                if (Modifier.isStatic(modifiers) || Modifier.isAbstract(modifiers)
                    || method.returnType != Void.TYPE
                    || method.isBridge || method.isSynthetic
                    || !Arrays.equals(method.parameterTypes, arrayOf<Any?>(CommentListAd))
                ) {
                    continue
                }
                if (result != null) {
                    return null // 不唯一就宁可不挂
                }
                result = method
            }
            if (result != null) {
                result.setAccessible(true)
            }
            return result
        }

        private fun loadLegacyModel(classLoader: ClassLoader): Class<*>? {
            for (name in LEGACY_MODELS) {
                val clazz = loadOptional(classLoader, name)
                if (clazz != null && looksLikeCommentListAd(clazz)) {
                    return clazz
                }
            }
            return null
        }

        private fun loadLegacyHolder(classLoader: ClassLoader): Class<*>? {
            for (name in LEGACY_HOLDERS) {
                val clazz = loadOptional(classLoader, name)
                if (clazz != null && looksLikeAdViewHolder(clazz, ViewHolder)) {
                    return clazz
                }
            }
            return null
        }

        private fun loadOptional(classLoader: ClassLoader?, name: String?): Class<*>? {
            try {
                return Class.forName(name, false, classLoader)
            } catch (e: Throwable) {
                return null
            }
        }

        private fun isEnabled(): Boolean {
            return Helper.prefs!!.getBoolean("switch_mainswitch", false)
                && Helper.prefs!!.getBoolean("switch_commentad", true)
        }

        /** 模型解析到了没有（单测断言用）。 */
        @JvmStatic
        fun hasModel(): Boolean {
            return CommentListAd != null
        }

        /** 有没有任何真实的拦截路径：参数解析或 ViewHolder 绑定，至少得有一条。 */
        @JvmStatic
        fun canIntercept(): Boolean {
            return resolveCommentAdParam != null || bindCommentAd != null
        }

        /** 有没有 ViewHolder 绑定这一条路径。 */
        @JvmStatic
        fun hasBindMethod(): Boolean {
            return bindCommentAd != null
        }

        /** 绑定方法必须是实例方法，hook 才能从 thisObject 拿到 itemView。 */
        @JvmStatic
        fun bindCarriesHolder(): Boolean {
            val method = bindCommentAd ?: return false
            return !Modifier.isStatic(method.modifiers)
        }
    }
}
