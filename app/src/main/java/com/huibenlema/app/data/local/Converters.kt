package com.huibenlema.app.data.local

import androidx.room.TypeConverter
import com.huibenlema.app.domain.model.CostCategory
import com.huibenlema.app.domain.model.PriceSource

/** Room 枚举转换器 */
class Converters {
    @TypeConverter
    fun priceSourceToString(v: PriceSource): String = v.name

    @TypeConverter
    fun stringToPriceSource(v: String): PriceSource = PriceSource.valueOf(v)

    @TypeConverter
    fun costCategoryToString(v: CostCategory): String = v.name

    @TypeConverter
    fun stringToCostCategory(v: String): CostCategory = CostCategory.valueOf(v)
}
