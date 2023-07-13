package com.cardlay.nebula.shared.query

abstract class Query<out T : Any> {
    abstract val key: List<Any>
    abstract val status: QueryStatus
    abstract val data: T?
    abstract val error: Throwable?

    abstract val isLoading: Boolean
}

abstract class InfiniteQuery<out T : Any, C : Any> {
    abstract val key: List<Any>
    abstract val start: C

    abstract val status: QueryStatus
    abstract val pages: List<Page<T, C>>?
    abstract val error: Throwable?

    abstract val isLoading: Boolean

    abstract fun next()

    abstract fun reload()
    abstract fun reload(cursor: C)

    data class Page<out T : Any, out C : Any>(val cursor: C, val data: T, val next: C?)
}
