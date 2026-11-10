package com.shatyuka.zhiliao.hooks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 评论广告的验证器必须真的在验：模型得是广告卡片模型的子类，绑定方法得是吃这个模型的实例方法。 */
class CommentAdValidatorTest {
    open class FakeAdBase

    class FakeAdModel : FakeAdBase() {
        @JvmField
        var position: Int = 0
    }

    class NotAnAdModel

    abstract class AbstractAdModel : FakeAdBase()

    class InstanceHolder : FakeAdBase() {
        fun bind(model: FakeAdModel) {
        }
    }

    class StaticHolder : FakeAdBase() {
        companion object {
            @JvmStatic
            fun bind(model: FakeAdModel) {
            }
        }
    }

    class NonBindHolder : FakeAdBase() {
        fun bind(model: String) {
        }
    }

    @Before
    fun reset() {
        CommentAd.CommentListAd = null
    }

    @Test
    fun modelMustExtendAdCardBase() {
        assertTrue(CommentAd.looksLikeAdCardModel(FakeAdModel::class.java, BASES))
        assertFalse(
            "与广告模型无关的类不能因为公开、非抽象就过关",
            CommentAd.looksLikeAdCardModel(NotAnAdModel::class.java, BASES)
        )
        assertFalse(
            "基类本身不是评论广告模型",
            CommentAd.looksLikeAdCardModel(FakeAdBase::class.java, BASES)
        )
        assertFalse(
            "抽象类不能当模型",
            CommentAd.looksLikeAdCardModel(AbstractAdModel::class.java, BASES)
        )
        assertFalse(CommentAd.looksLikeAdCardModel(Any::class.java, BASES))
    }

    @Test
    fun unknownBaseNeverMatches() {
        assertFalse(
            CommentAd.looksLikeAdCardModel(
                FakeAdModel::class.java,
                arrayOf("not.on.classpath.Missing")
            )
        )
        assertFalse(CommentAd.looksLikeAdCardModel(FakeAdModel::class.java, arrayOf()))
    }

    @Test
    fun bindMethodMustBeInstanceAndTakeTheModel() {
        CommentAd.CommentListAd = FakeAdModel::class.java
        assertTrue(CommentAd.looksLikeAdViewHolder(InstanceHolder::class.java, FakeAdBase::class.java))
        assertFalse(
            "static 绑定拿不到 thisObject，hook 里就取不了 itemView",
            CommentAd.looksLikeAdViewHolder(StaticHolder::class.java, FakeAdBase::class.java)
        )
        assertFalse(CommentAd.looksLikeAdViewHolder(NonBindHolder::class.java, FakeAdBase::class.java))
        assertFalse(
            "基类自身不是 ViewHolder",
            CommentAd.looksLikeAdViewHolder(FakeAdBase::class.java, FakeAdBase::class.java)
        )
    }

    companion object {
        private val BASES = arrayOf(FakeAdBase::class.java.name)
    }
}
