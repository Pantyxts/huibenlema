package com.huibenlema.app.data.local

import com.huibenlema.app.data.local.entity.BookEntity
import com.huibenlema.app.data.local.entity.CostItemEntity
import com.huibenlema.app.data.local.entity.DailyStatEntity
import com.huibenlema.app.domain.model.Book
import com.huibenlema.app.domain.model.CostItem
import com.huibenlema.app.domain.model.DailyStat

/** Entity ↔ 领域模型 映射 */

fun BookEntity.toDomain() = Book(
    bookId = bookId,
    title = title,
    author = author,
    translator = translator,
    publisher = publisher,
    coverUrl = coverUrl,
    isbn = isbn,
    category = category,
    priceFen = priceFen,
    priceSource = priceSource,
    priceUpdatedAt = priceUpdatedAt,
    progress = progress,
    finished = finished,
    totalReadSeconds = totalReadSeconds,
    lastReadAt = lastReadAt,
    onShelf = onShelf,
    removed = removed
)

fun Book.toEntity(now: Long = System.currentTimeMillis()) = BookEntity(
    bookId = bookId,
    title = title,
    author = author,
    translator = translator,
    publisher = publisher,
    coverUrl = coverUrl,
    isbn = isbn,
    category = category,
    priceFen = priceFen,
    priceSource = priceSource,
    priceUpdatedAt = priceUpdatedAt,
    progress = progress,
    finished = finished,
    totalReadSeconds = totalReadSeconds,
    lastReadAt = lastReadAt,
    onShelf = onShelf,
    removed = false,
    createdAt = now,
    updatedAt = now
)

fun CostItemEntity.toDomain() = CostItem(
    id = id,
    name = name,
    priceFen = priceFen,
    category = category,
    boughtAt = boughtAt,
    note = note
)

fun CostItem.toEntity() = CostItemEntity(
    id = id,
    name = name,
    priceFen = priceFen,
    category = category,
    boughtAt = boughtAt,
    note = note
)

fun DailyStatEntity.toDomain() = DailyStat(
    date = date,
    valueFen = valueFen,
    readSeconds = readSeconds,
    bookCount = bookCount
)
