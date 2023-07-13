package com.cardlay.nebula.shared.query

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface QueryClient {
    fun <T : Any> useQuery(key: List<Any>, query: suspend (List<Any>) -> T): Flow<Query<T>>
    fun <T : Any> useStateQuery(key: List<Any>, query: suspend (List<Any>) -> T): StateFlow<Query<T>>

    suspend fun invalidate(prefix: List<Any>)
    suspend fun invalidate(predicate: (Query<*>) -> Boolean)

    suspend fun reload(prefix: List<Any>)
    suspend fun reload(predicate: (Query<*>) -> Boolean)

    suspend fun remove(prefix: List<Any>)
    suspend fun remove(predicate: (Query<*>) -> Boolean)
    fun <T : Any> get(key: List<Any>): Query<T>?
    fun <T : Any, C : Any> useInfiniteQuery(
        key: List<Any>,
        start: C,
        query: suspend (List<Any>, C) -> InfiniteQuery.Page<T, C>
    ): Flow<InfiniteQuery<T, C>>
}
