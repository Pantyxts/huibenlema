package com.huibenlema.app.domain.calculator

import com.huibenlema.app.domain.model.Book
import com.huibenlema.app.domain.model.CostCategory
import com.huibenlema.app.domain.model.CostItem
import com.huibenlema.app.domain.model.PriceSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaybackCalculatorTest {

    private fun book(
        id: String,
        priceFen: Long,
        progress: Double,
        source: PriceSource = PriceSource.WEREAD
    ) = Book(bookId = id, title = "书$id", priceFen = priceFen, progress = progress, priceSource = source)

    private fun device(fen: Long) = CostItem(name = "设备", priceFen = fen, category = CostCategory.DEVICE)

    @Test
    fun `半本100元书加整本50元书_成本110元`() {
        val books = listOf(book("1", 10000, 0.5), book("2", 5000, 1.0))
        val s = PaybackCalculator.compute(books, listOf(device(11000)))

        assertEquals(10000L, s.totalValueFen)      // 50 + 50 = 100 元
        assertEquals(11000L, s.totalCostFen)
        assertEquals(-1000L, s.diffFen)
        assertEquals(10000.0 / 11000.0, s.ratio, 1e-9)
        assertEquals(7500L, s.avgPriceFen)         // (100+50)/2 = 75 元
        assertEquals(1L, s.booksToGo)              // ceil(1000/7500) = 1
        assertEquals(2, s.pricedBookCount)
        assertEquals(0, s.unpricedBookCount)
    }

    @Test
    fun `进度为零的书不计入价值也不进平均书价`() {
        val books = listOf(book("1", 10000, 0.0), book("2", 5000, 1.0))
        val s = PaybackCalculator.compute(books, listOf(device(11000)))
        assertEquals(5000L, s.totalValueFen)
        assertEquals(5000L, s.avgPriceFen)
    }

    @Test
    fun `免费书不贡献价值不进平均书价但计入总数`() {
        val books = listOf(book("1", 0, 0.5), book("2", 5000, 1.0))
        val s = PaybackCalculator.compute(books, listOf(device(1000)))
        assertEquals(5000L, s.totalValueFen)
        assertEquals(5000L, s.avgPriceFen)
        assertEquals(2, s.totalBooks)
    }

    @Test
    fun `未定价书计入未定价计数_贡献价值为零`() {
        val books = listOf(
            book("1", 0, 0.5, source = PriceSource.NONE),
            book("2", 5000, 1.0)
        )
        val s = PaybackCalculator.compute(books, listOf(device(1000)))
        assertEquals(1, s.unpricedBookCount)
        assertEquals(1, s.pricedBookCount)
        assertEquals(5000L, s.totalValueFen)
    }

    @Test
    fun `成本为零时比率为无穷且不除零`() {
        val books = listOf(book("1", 10000, 0.5))
        val s = PaybackCalculator.compute(books, emptyList())
        assertEquals(Double.POSITIVE_INFINITY, s.ratio, 0.0)
        assertTrue(!s.hasCost)
        assertEquals(0L, s.booksToGo)
    }

    @Test
    fun `已回本时booksToGo为零`() {
        val books = listOf(book("1", 10000, 1.0))
        val s = PaybackCalculator.compute(books, listOf(device(5000)))
        assertEquals(5000L, s.diffFen)
        assertEquals(2.0, s.ratio, 1e-9)
        assertEquals(0L, s.booksToGo)
    }

    @Test
    fun `全部书免费或未定价时平均书价为零_booksToGo为零不除零`() {
        val books = listOf(book("1", 0, 0.5), book("2", 0, 0.5, source = PriceSource.NONE))
        val s = PaybackCalculator.compute(books, listOf(device(11000)))
        assertEquals(0L, s.avgPriceFen)
        assertEquals(0L, s.booksToGo)
    }

    @Test
    fun `进度超过1按满进度计`() {
        val s = PaybackCalculator.compute(listOf(book("1", 10000, 1.2)), listOf(device(11000)))
        assertEquals(10000L, s.totalValueFen)
    }

    @Test
    fun `每日价值增量_负delta钳制为零_未知书忽略`() {
        val booksById = mapOf(
            "1" to book("1", 10000, 0.5),
            "2" to book("2", 5000, 1.0)
        )
        val delta = mapOf(
            "1" to 0.25,   // 0.25 × 100 元 = 25 元
            "2" to -0.5,   // 重读重置 → 钳制 0
            "9" to 0.3     // 不存在 → 忽略
        )
        assertEquals(2500L, PaybackCalculator.dailyValueDelta(delta, booksById))
    }
}
