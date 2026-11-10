package com.shatyuka.zhiliao.hooks

import android.annotation.SuppressLint
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RelativeLayout
import android.widget.TextView
import com.shatyuka.zhiliao.Helper
import com.shatyuka.zhiliao.R
import com.shatyuka.zhiliao.xposed.XC_MethodHook
import com.shatyuka.zhiliao.xposed.XC_MethodHook.MethodHookParam
import com.shatyuka.zhiliao.xposed.XposedBridge
import java.lang.reflect.Field
import java.lang.reflect.Method

class Tag : IHook {
    private val TAG_ID = 0xABCDEF

    override fun getName(): String {
        return "显示卡片类别"
    }

    override fun init(classLoader: ClassLoader) {
        Helper.requireTarget(Helper.DataUnique_type, "com.zhihu.android.api.model.template.DataUnique.type")
        BaseTemplateNewFeedHolder = classLoader.loadClass("com.zhihu.android.app.feed.ui.holder.template.optimal.BaseTemplateNewFeedHolder")
        TemplateFeed = classLoader.loadClass("com.zhihu.android.api.model.template.TemplateFeed")
        ViewHolder = classLoader.loadClass("androidx.recyclerview.widget.RecyclerView\$ViewHolder")
        SugarHolder = classLoader.loadClass("com.zhihu.android.sugaradapter.SugarHolder")
        TemplateRoot = classLoader.loadClass("com.zhihu.android.api.model.template.TemplateRoot")

        ViewHolder_itemView = ViewHolder!!.getField("itemView")
        try {
            SugarHolder_mData = SugarHolder!!.getDeclaredField("c")
        } catch (_: NoSuchFieldException) {
            SugarHolder_mData = SugarHolder!!.getDeclaredField("mData")
        }
        SugarHolder_mData!!.isAccessible = true
        TemplateRoot_unique = TemplateRoot!!.getField("unique")

        try {
            onBindData = BaseTemplateNewFeedHolder!!.getMethod("onBindData", Any::class.java)
        } catch (_: NoSuchMethodException) {
            onBindData = BaseTemplateNewFeedHolder!!.getDeclaredMethod("a", TemplateFeed)
        }

        var sduiCard: Class<*>? = null
        try {
            sduiCard = classLoader.loadClass("com.zhihu.android.app.feed.ui.holder.template.optimal.SDUICard")
        } catch (_: ClassNotFoundException) {
        }
        if (sduiCard != null) {
            card_onBindData = sduiCard.getMethod("onBindData", Any::class.java)

            SDUICard_view = Helper.findFieldByType(sduiCard!!, View::class.java)
            if (SDUICard_view == null) {
                throw NoSuchFieldException("view")
            }
            SDUICard_view!!.isAccessible = true

            val card = classLoader.loadClass("com.zhihu.android.ui.shared.sdui.model.Card")
            Card_elements = card.getDeclaredField("elements")
            Card_elements!!.isAccessible = true
            Card_extra = card.getDeclaredField("extra")
            Card_extra!!.isAccessible = true
            Card_Extra_contentType = Card_extra!!.type.getDeclaredField("contentType")
            Card_Extra_contentType!!.isAccessible = true

            Element = classLoader.loadClass("com.zhihu.android.ui.shared.sdui.model.Element")
            Element_id = Element!!.getDeclaredField("id")
            Element_id!!.isAccessible = true
            Line = classLoader.loadClass("com.zhihu.android.ui.shared.sdui.model.Line")
            Line_elements = Line!!.getDeclaredField("elements")
            Line_elements!!.isAccessible = true
            Avatar = classLoader.loadClass("com.zhihu.android.ui.shared.sdui.model.Avatar")
        }
    }

    @SuppressLint("DiscouragedApi")
    override fun hook() {
        if (Helper.prefs!!.getBoolean("switch_mainswitch", false) && Helper.prefs!!.getBoolean("switch_tag", false)) {
            XposedBridge.hookMethod(onBindData!!, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val thisObject = param.thisObject
                    val itemView = ViewHolder_itemView!!.get(thisObject)
                    if (itemView !is ViewGroup) {
                        return
                    }
                    val viewGroup = itemView

                    val title = viewGroup.findViewById<TextView>(
                        Helper.context!!.resources.getIdentifier("title", "id", Helper.hookPackage),
                    )
                    val author = viewGroup.findViewById<View>(
                        Helper.context!!.resources.getIdentifier("author", "id", Helper.hookPackage),
                    )
                    if (title == null) {
                        return
                    }

                    val templateFeed = SugarHolder_mData!!.get(thisObject)
                    if (!TemplateRoot!!.isInstance(templateFeed)) {
                        return
                    }
                    val unique = TemplateRoot_unique!!.get(templateFeed)
                    if (!Helper.DataUnique_type!!.declaringClass.isInstance(unique)) {
                        return
                    }
                    val type = Helper.DataUnique_type!!.get(unique) as String?

                    postProcessTag(title, author, viewGroup, type, false)
                }
            })

            if (card_onBindData != null) {
                XposedBridge.hookMethod(card_onBindData!!, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val thisObject = param.thisObject
                        if (param.args.isEmpty() || !Card_extra!!.declaringClass.isInstance(param.args[0])) {
                            return
                        }
                        val card = param.args[0]
                        val extra = Card_extra!!.get(card) ?: return
                        val type = Card_Extra_contentType!!.get(extra) as String?

                        val viewObject = SDUICard_view!!.get(thisObject)
                        if (viewObject !is FrameLayout) {
                            return
                        }
                        val layout = viewObject
                        if (layout.childCount != 1) {
                            return
                        }
                        val layoutChild = layout.getChildAt(0)
                        if (layoutChild !is ViewGroup) {
                            return
                        }
                        val cardView = layoutChild

                        val elements = Card_elements!!.get(card) as List<*>?
                        if (elements == null) {
                            return
                        }
                        var hasReason = false
                        var title: TextView? = null
                        var author: View? = null
                        for (element in elements) {
                            if (!Element!!.isInstance(element)) {
                                continue
                            }
                            val id = Element_id!!.get(element) as String?
                            if (id == null) {
                                continue
                            }
                            if (title == null && author == null && id == "Text") {
                                val index = elements.indexOf(element)
                                if (index != 0) {
                                    hasReason = true
                                }
                                val child = cardView.getChildAt(index)
                                if (child is TextView) {
                                    title = child
                                }
                                continue
                            }
                            if (author == null && element!!.javaClass === Line && id == "0") {
                                val lineElements = Line_elements!!.get(element) as List<*>?
                                if (lineElements != null && lineElements.isNotEmpty()) {
                                    val lineElement = lineElements[0]
                                    if (lineElement != null && lineElement.javaClass === Avatar) {
                                        val index = elements.indexOf(element)
                                        if (title == null && index != 0) {
                                            hasReason = true
                                        }
                                        val child = cardView.getChildAt(index)
                                        if (child is ViewGroup) {
                                            author = child
                                        }
                                        break
                                    }
                                }
                            }
                        }

                        postProcessTag(title, author, cardView, type, hasReason)
                    }
                })
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun postProcessTag(title: TextView?, author: View?, viewGroup: ViewGroup, type: String?, hasReason: Boolean) {
        if (author == null) {
            return
        }

        val tagLayout: RelativeLayout
        var tag = viewGroup.findViewById<TextView>(TAG_ID)
        if (tag == null) {
            tag = TextView(viewGroup.context)
            tag.id = TAG_ID

            tagLayout = RelativeLayout(viewGroup.context)
            tagLayout.addView(tag)
            viewGroup.addView(tagLayout)
        } else {
            val tagParent = tag.parent
            if (tagParent !is RelativeLayout) {
                return
            }
            tagLayout = tagParent
        }

        tag.setTextColor(-1)
        tag.text = getType(type)
        tag.background = getBackground(type)

        var baseX = 0
        if (title != null && title.layoutParams is ViewGroup.MarginLayoutParams) {
            baseX = (title.layoutParams as ViewGroup.MarginLayoutParams).leftMargin
            if (baseX != 0 && author.layoutParams is ViewGroup.MarginLayoutParams) {
                (author.layoutParams as ViewGroup.MarginLayoutParams).leftMargin = baseX
            }
        }
        if (baseX != 0) {
            tagLayout.x = baseX.toFloat()
        }

        val hasTitle = title != null && title.visibility == View.VISIBLE

        var baseY = 0f
        if (hasReason) {
            baseY = Helper.scale * 21
        }
        if (hasTitle) {
            tagLayout.y = (Helper.scale * 3 + 0.5).toFloat() + baseY
        } else {
            tagLayout.y = baseY
        }

        if (hasTitle) {
            title!!.text = "\u3000\u3000 " + title.text
        } else if (author.layoutParams is ViewGroup.MarginLayoutParams) {
            (author.layoutParams as ViewGroup.MarginLayoutParams).leftMargin = (Helper.scale * 40 + 0.5 + baseX).toInt()
        }
    }

    companion object {
        @JvmField
        var backgrounds: Array<Drawable>? = null

        @JvmField
        var BaseTemplateNewFeedHolder: Class<*>? = null

        @JvmField
        var TemplateFeed: Class<*>? = null

        @JvmField
        var ViewHolder: Class<*>? = null

        @JvmField
        var SugarHolder: Class<*>? = null

        @JvmField
        var TemplateRoot: Class<*>? = null

        @JvmField
        var Element: Class<*>? = null

        @JvmField
        var Line: Class<*>? = null

        @JvmField
        var Avatar: Class<*>? = null

        @JvmField
        var onBindData: Method? = null

        @JvmField
        var card_onBindData: Method? = null

        @JvmField
        var ViewHolder_itemView: Field? = null

        @JvmField
        var SugarHolder_mData: Field? = null

        @JvmField
        var TemplateRoot_unique: Field? = null

        @JvmField
        var SDUICard_view: Field? = null

        @JvmField
        var Card_elements: Field? = null

        @JvmField
        var Card_extra: Field? = null

        @JvmField
        var Card_Extra_contentType: Field? = null

        @JvmField
        var Element_id: Field? = null

        @JvmField
        var Line_elements: Field? = null

        private fun getType(type: String?): String {
            if (type == null) {
                return "其他"
            }
            return when (type) {
                "answer", "Answer" -> "问题"
                "article", "Post" -> "文章"
                "zvideo" -> "视频"
                "drama" -> "直播"
                "pin" -> "想法"
                else -> "其他"
            }
        }

        @Suppress("DEPRECATION")
        private fun getBackground(type: String?): Drawable {
            if (backgrounds == null) {
                backgrounds = arrayOf(
                    Helper.modRes!!.getDrawable(R.drawable.bg_answer),
                    Helper.modRes!!.getDrawable(R.drawable.bg_article),
                    Helper.modRes!!.getDrawable(R.drawable.bg_video),
                    Helper.modRes!!.getDrawable(R.drawable.bg_pin),
                    Helper.modRes!!.getDrawable(R.drawable.bg_others),
                )
            }
            val cached = backgrounds!!
            if (type == null) {
                return cached[4]
            }
            return when (type) {
                "answer", "Answer" -> cached[0]
                "article", "Post" -> cached[1]
                "zvideo", "drama" -> cached[2]
                "pin" -> cached[3]
                else -> cached[4]
            }
        }
    }
}
