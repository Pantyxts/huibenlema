package com.huibenlema.app.domain.model

/**
 * 定价来源。
 * MANUAL（用户手改）优先级最高，重新同步时不被 API 价格覆盖。
 */
enum class PriceSource { WEREAD, ISBN, MANUAL, NONE }
