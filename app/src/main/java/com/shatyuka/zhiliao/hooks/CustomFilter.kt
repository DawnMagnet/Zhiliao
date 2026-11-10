package com.shatyuka.zhiliao.hooks

import com.shatyuka.zhiliao.Helper
import com.shatyuka.zhiliao.xposed.XC_MethodHook
import com.shatyuka.zhiliao.xposed.XC_MethodHook.MethodHookParam
import com.shatyuka.zhiliao.xposed.XposedBridge
import java.lang.reflect.Field

class CustomFilter : IHook {
    override fun getName(): String {
        return "自定义过滤"
    }

    override fun init(classLoader: ClassLoader) {
        Helper.requireTarget(Helper.DataUnique_type!!, "com.zhihu.android.api.model.template.DataUnique.type")
        InnerDeserializer = classLoader.loadClass("com.zhihu.android.api.util.ZHObjectRegistryCenter" + '$' + "InnerDeserializer")
        ApiTemplateRoot = classLoader.loadClass("com.zhihu.android.api.model.template.api.ApiTemplateRoot")
        ApiFeedCard = classLoader.loadClass("com.zhihu.android.api.model.template.api.ApiFeedCard")
        MarketCard = classLoader.loadClass("com.zhihu.android.api.model.MarketCard")
        ApiFeedContent = classLoader.loadClass("com.zhihu.android.api.model.template.api.ApiFeedContent")
        ApiText = classLoader.loadClass("com.zhihu.android.api.model.template.api.ApiText")
        ApiLine = classLoader.loadClass("com.zhihu.android.api.model.template.api.ApiLine")
        ApiElement = classLoader.loadClass("com.zhihu.android.api.model.template.api.ApiElement")
        try {
            Card = classLoader.loadClass("com.zhihu.android.ui.shared.sdui.model.Card")
        } catch (ignored: ClassNotFoundException) {
        }
        if (Card != null) {
            val cardClass = Card!!
            val elementsField = cardClass.getDeclaredField("elements")
            elementsField.setAccessible(true)
            Card_elements = elementsField
            val extraField = cardClass.getDeclaredField("extra")
            extraField.setAccessible(true)
            Card_extra = extraField
            val contentTypeField = extraField.type.getDeclaredField("contentType")
            contentTypeField.setAccessible(true)
            Card_Extra_contentType = contentTypeField

            Element = classLoader.loadClass("com.zhihu.android.ui.shared.sdui.model.Element")
            val elementId = Element!!.getDeclaredField("id")
            elementId.setAccessible(true)
            Element_id = elementId
            TextElement = classLoader.loadClass("com.zhihu.android.ui.shared.sdui.model.TextElement")
            val textElementText = TextElement!!.getDeclaredField("text")
            textElementText.setAccessible(true)
            TextElement_text = textElementText
            try {
                HtmlTextElement = classLoader.loadClass("com.zhihu.android.ui.shared.sdui.model.HtmlTextElement")
                val htmlText = HtmlTextElement!!.getDeclaredField("text")
                htmlText.setAccessible(true)
                HtmlTextElement_text = htmlText
            } catch (ignored: ClassNotFoundException) {
                HtmlTextElement = null
                HtmlTextElement_text = null
            } catch (ignored: NoSuchFieldException) {
                HtmlTextElement = null
                HtmlTextElement_text = null
            }
            Line = classLoader.loadClass("com.zhihu.android.ui.shared.sdui.model.Line")
            val lineElements = Line!!.getDeclaredField("elements")
            lineElements.setAccessible(true)
            Line_elements = lineElements
            Avatar = classLoader.loadClass("com.zhihu.android.ui.shared.sdui.model.Avatar")
            Video = classLoader.loadClass("com.zhihu.android.ui.shared.sdui.model.Video")
            Author = null
            Author_fistLine = null
            try {
                Author = classLoader.loadClass("com.zhihu.android.ui.shared.sdui.model.Author")
                val firstLine = Author!!.getDeclaredField("fistLine")
                firstLine.setAccessible(true)
                Author_fistLine = firstLine
            } catch (ignored: ClassNotFoundException) {
                Author = null
                Author_fistLine = null
            } catch (ignored: NoSuchFieldException) {
                Author = null
                Author_fistLine = null
            }
        }

        val templateRoot = ApiTemplateRoot!!
        val extraField = templateRoot.getField("extra")
        ApiTemplateRoot_extra = extraField
        ApiTemplateRoot_common_card = templateRoot.getField("common_card")
        ApiFeedCard_feed_content = ApiFeedCard!!.getField("feed_content")
        val feedContent = ApiFeedContent!!
        ApiFeedContent_title = feedContent.getField("title")
        ApiFeedContent_content = feedContent.getField("content")
        ApiFeedContent_sourceLine = feedContent.getField("sourceLine")
        ApiFeedContent_video = feedContent.getField("video")
        ApiText_panel_text = ApiText!!.getField("panel_text")
        ApiLine_elements = ApiLine!!.getField("elements")
        ApiElement_text = ApiElement!!.getField("text")
    }

    override fun hook() {
        XposedBridge.hookAllMethods(InnerDeserializer!!, "deserialize", object : XC_MethodHook() {
            @Suppress("UNCHECKED_CAST")
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!Helper.prefs!!.getBoolean("switch_mainswitch", false)) {
                    return
                }
                val result = param.result ?: return
                val resultClass = result.javaClass
                if (resultClass === ApiTemplateRoot) {
                    val extra = ApiTemplateRoot_extra!!.get(result)
                    val type = if (extra == null) null else Helper.DataUnique_type!!.get(extra) as String?
                    var hasVideo = false
                    var title = ""
                    var author = ""
                    var content = ""
                    val commonCard = ApiTemplateRoot_common_card!!.get(result)
                    val feedContent = if (ApiFeedCard!!.isInstance(commonCard)) ApiFeedCard_feed_content!!.get(commonCard) else null
                    if (feedContent != null) {
                        hasVideo = ApiFeedContent_video!!.get(feedContent) != null

                        val panelTitle = readApiText(ApiFeedContent_title!!.get(feedContent))
                        if (panelTitle != null) {
                            title = panelTitle
                        }

                        val sourceLine = ApiFeedContent_sourceLine!!.get(feedContent)
                        val elements = if (ApiLine!!.isInstance(sourceLine)) {
                            ApiLine_elements!!.get(sourceLine) as List<*>?
                        } else {
                            null
                        }
                        if (elements != null && elements.size > 1) {
                            val panelAuthor = readApiElementText(elements[1])
                            if (panelAuthor != null) {
                                author = panelAuthor
                            }
                        }

                        val panelContent = readApiText(ApiFeedContent_content!!.get(feedContent))
                        if (panelContent != null) {
                            content = panelContent
                        }
                    }

                    if (filter(type, hasVideo, title, author, content)) {
                        param.result = null
                    }
                } else if (resultClass === Card) {
                    val extra = Card_extra!!.get(result)
                    if (extra == null) {
                        return
                    }
                    val type = Card_Extra_contentType!!.get(extra) as String?
                    var hasVideo = false
                    var title: String? = null
                    var author: String? = null
                    var content: String? = null
                    val elements = Card_elements!!.get(result) as List<*>?
                    if (elements != null) {
                        for (element in elements) {
                            if (element == null || !Element!!.isInstance(element)) {
                                continue
                            }
                            val id = Element_id!!.get(element) as String?
                            if (id == null) {
                                continue
                            }
                            if (title == null && author == null && id == "Text") {
                                title = readElementText(element)
                                continue
                            }
                            if (author == null && content == null && element.javaClass === Line && id == "0") {
                                val lineElements = Line_elements!!.get(element) as List<*>?
                                if (lineElements != null && lineElements.isNotEmpty()) {
                                    val avatarElement = lineElements[0]
                                    if (avatarElement != null && avatarElement.javaClass === Avatar) {
                                        author = readLineFirstText(lineElements)
                                        continue
                                    }
                                }
                            }
                            // 11.10.0 起作者行也可能由 Author 元素承载，取其中第一行的首个文本元素
                            if (author == null && content == null && Author != null && element.javaClass === Author) {
                                val firstLine = Author_fistLine!!.get(element)
                                val authorText = if (firstLine != null && firstLine.javaClass === Line) {
                                    readLineFirstText(Line_elements!!.get(firstLine) as List<*>?)
                                } else {
                                    null
                                }
                                if (authorText != null) {
                                    author = authorText
                                    continue
                                }
                            }
                            if (content == null && id.endsWith("_summary")) {
                                content = readElementText(element)
                            }
                            if (element.javaClass === Video) {
                                hasVideo = true
                            }
                        }
                    }

                    if (filter(type, hasVideo, title, author, content)) {
                        param.result = null
                    }
                } else if (resultClass === MarketCard) {
                    if (Helper.prefs!!.getBoolean("switch_marketcard", false)) {
                        param.result = null
                    }
                }
            }
        })
    }

    private fun readApiText(apiText: Any?): String? {
        if (!ApiText!!.isInstance(apiText)) {
            return null
        }
        val text = ApiText_panel_text!!.get(apiText)
        return if (text is String) text else null
    }

    private fun readApiElementText(apiElement: Any?): String? {
        if (!ApiElement!!.isInstance(apiElement)) {
            return null
        }
        return readApiText(ApiElement_text!!.get(apiElement))
    }

    private fun readElementText(element: Any?): String? {
        if (element == null) {
            return null
        }
        if (element.javaClass === TextElement) {
            return TextElement_text!!.get(element) as String?
        }
        if (element.javaClass === HtmlTextElement) {
            return HtmlTextElement_text!!.get(element) as String?
        }
        return null
    }

    private fun readLineFirstText(lineElements: List<*>?): String? {
        if (lineElements == null) {
            return null
        }
        for (lineElement in lineElements) {
            val text = readElementText(lineElement)
            if (text != null) {
                return text
            }
        }
        return null
    }

    private fun filter(type: String?, hasVideo: Boolean, title: String?, author: String?, content: String?): Boolean {
        if (type == null) {
            return false
        }
        if (Helper.prefs!!.getBoolean("switch_video", false) && (type == "zvideo" || type == "drama" || hasVideo)) {
            return true
        }
        if (Helper.prefs!!.getBoolean("switch_removearticle", false) && (type == "article" || type == "Post")) {
            return true
        }
        if (Helper.prefs!!.getBoolean("switch_pin", false) && type == "pin") {
            return true
        }
        if (Helper.prefs!!.getBoolean("switch_feedad", true) && type == "SvipActivity") {
            return true
        }

        val titleRegex = Helper.regex_title
        if (titleRegex != null && title != null && titleRegex.matcher(title).find()) {
            return true
        }
        val authorRegex = Helper.regex_author
        if (authorRegex != null && author != null && authorRegex.matcher(author).find()) {
            return true
        }
        val contentRegex = Helper.regex_content
        if (contentRegex != null && content != null && contentRegex.matcher(content).find()) {
            return true
        }

        return false
    }

    companion object {
        @JvmField
        var InnerDeserializer: Class<*>? = null

        @JvmField
        var ApiTemplateRoot: Class<*>? = null

        @JvmField
        var ApiFeedCard: Class<*>? = null

        @JvmField
        var MarketCard: Class<*>? = null

        @JvmField
        var ApiFeedContent: Class<*>? = null

        @JvmField
        var ApiText: Class<*>? = null

        @JvmField
        var ApiLine: Class<*>? = null

        @JvmField
        var ApiElement: Class<*>? = null

        @JvmField
        var Card: Class<*>? = null

        @JvmField
        var Element: Class<*>? = null

        @JvmField
        var TextElement: Class<*>? = null

        @JvmField
        var HtmlTextElement: Class<*>? = null

        @JvmField
        var Line: Class<*>? = null

        @JvmField
        var Avatar: Class<*>? = null

        @JvmField
        var Video: Class<*>? = null

        @JvmField
        var Author: Class<*>? = null

        @JvmField
        var ApiTemplateRoot_extra: Field? = null

        @JvmField
        var ApiTemplateRoot_common_card: Field? = null

        @JvmField
        var ApiFeedCard_feed_content: Field? = null

        @JvmField
        var ApiFeedContent_title: Field? = null

        @JvmField
        var ApiFeedContent_content: Field? = null

        @JvmField
        var ApiFeedContent_sourceLine: Field? = null

        @JvmField
        var ApiFeedContent_video: Field? = null

        @JvmField
        var ApiText_panel_text: Field? = null

        @JvmField
        var ApiLine_elements: Field? = null

        @JvmField
        var ApiElement_text: Field? = null

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

        @JvmField
        var TextElement_text: Field? = null

        @JvmField
        var HtmlTextElement_text: Field? = null

        @JvmField
        var Author_fistLine: Field? = null
    }
}
