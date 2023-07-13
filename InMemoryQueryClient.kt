package com.cardlay.nebula.shared.query

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

class InMemoryQueryClient : QueryClient {
    private val changes = MutableSharedFlow<List<Any>>()

    private val queriesLock = Mutex()
    private val queries = mutableListOf<MutableQuery<*>>()

    private val infiniteQueries = mutableListOf<MutableInfiniteQuery<*, *>>()

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun cancel() {
        coroutineScope.cancel()
    }

    override fun <T : Any> useStateQuery(
        key: List<Any>,
        query: suspend (List<Any>) -> T,
    ): StateFlow<Query<T>> {
        val current = get(key) ?: MutableQuery(key, query, coroutineScope)
        return useQuery(key, query)
            .stateIn(coroutineScope, SharingStarted.WhileSubscribed(), current)
    }

    override fun <T : Any> useQuery(
        key: List<Any>,
        query: suspend (List<Any>) -> T,
    ): Flow<Query<T>> {
        return flow {
            emitValue(key, query)

            changes.collect { changed ->
                if (key.startsWith(changed)) {
                    emitValue(key, query)
                }
            }
        }
    }

    override fun <T : Any, C : Any> useInfiniteQuery(
        key: List<Any>,
        start: C,
        query: suspend (List<Any>, C) -> InfiniteQuery.Page<T, C>,
    ): Flow<InfiniteQuery<T, C>> {
        return flow {
            emitValue(key, start, query)

            changes.collect { changed ->
                if (key.startsWith(changed)) {
                    emitValue(key, start, query)
                }
            }
        }
    }

    override fun <T : Any> get(key: List<Any>): Query<T>? {
        return uncheckedCast(queries.firstOrNull { it.key == key })
    }

    override suspend fun invalidate(prefix: List<Any>) {
        invalidate { query -> query.key.startsWith(prefix) }
    }

    override suspend fun invalidate(predicate: (Query<*>) -> Boolean) {
        queriesLock.withLock {
            for (idx in queries.indices) {
                val query = queries[idx]
                if (predicate(query)) {
                    if (query.status == QueryStatus.SUCCESS || query.status == QueryStatus.ERROR) {
                        query.lock.withLock {
                            if (query.status == QueryStatus.SUCCESS || query.status == QueryStatus.ERROR) {
                                query.status = QueryStatus.IDLE
                                query.cancel()
                            }
                        }
                    }

                    changes.emit(query.key)
                }
            }
        }
    }

    override suspend fun reload(prefix: List<Any>) {
        reload { query -> query.key.startsWith(prefix) }
    }

    override suspend fun reload(predicate: (Query<*>) -> Boolean) {
        queriesLock.withLock {
            for (idx in queries.indices) {
                val query = queries[idx]
                if (predicate(query)) {
                    if (query.status == QueryStatus.SUCCESS || query.status == QueryStatus.ERROR) {
                        query.lock.withLock {
                            if (query.status == QueryStatus.SUCCESS || query.status == QueryStatus.ERROR) {
                                query.status = QueryStatus.IDLE
                                query.cancel()
                                query.load()

                                changes.emit(query.key)
                            }
                        }
                    }
                }
            }
        }
    }

    override suspend fun remove(prefix: List<Any>) {
        remove { query -> query.key.startsWith(prefix) }
    }

    override suspend fun remove(predicate: (Query<*>) -> Boolean) {
        queriesLock.withLock {
            for (idx in queries.indices) {
                val query = queries[idx]
                if (predicate(query)) {
                    queries.removeAt(idx)
                    changes.emit(query.key)
                }
            }
        }
    }

    private suspend fun <T : Any> FlowCollector<Query<T>>.emitValue(
        key: List<Any>,
        provider: suspend (List<Any>) -> T,
    ) {
        val rawQuery = queriesLock.withDoubleCheckedLock(
            get = { queries.firstOrNull { it.key == key } },
            defaultValue = { MutableQuery(key, provider, coroutineScope) },
            set = { queries.add(it) },
        )

        val query = uncheckedCast<MutableQuery<T>>(rawQuery)
        if (query.status == QueryStatus.IDLE || query.status == QueryStatus.ERROR) {
            query.lock.withLock {
                if (query.status == QueryStatus.IDLE || query.status == QueryStatus.ERROR) {
                    query.load()
                }
            }
        }

        emit(query)
    }

    private suspend fun <T : Any, C : Any> FlowCollector<InfiniteQuery<T, C>>.emitValue(
        key: List<Any>,
        start: C,
        provider: suspend (List<Any>, C) -> InfiniteQuery.Page<T, C>,
    ) {
        val rawQuery = queriesLock.withDoubleCheckedLock(
            get = { infiniteQueries.firstOrNull { it.key == key } },
            defaultValue = { MutableInfiniteQuery(key, start, provider, coroutineScope) },
            set = { infiniteQueries.add(it) },
        )

        val query = uncheckedCast<MutableInfiniteQuery<T, C>>(rawQuery)
        if (query.status == QueryStatus.IDLE || query.status == QueryStatus.ERROR) {
            query.lock.withLock {
                if (query.status == QueryStatus.IDLE || query.status == QueryStatus.ERROR) {
                    query.load()
                }
            }
        }

        emit(query)
    }

    @OptIn(ExperimentalContracts::class)
    private suspend inline fun <R : Any> Mutex.withDoubleCheckedLock(
        get: () -> R?,
        defaultValue: () -> R,
        set: (R) -> Unit,
    ): R {
        contract {
            callsInPlace(defaultValue, InvocationKind.AT_MOST_ONCE)
        }

        val unlockedValue = get()
        if (unlockedValue != null) {
            return unlockedValue
        }

        return withLock {
            val lockedValue = get()
            if (lockedValue != null) {
                return@withLock lockedValue
            }

            val newValue = defaultValue()
            set(newValue)
            return@withLock newValue
        }
    }

    private class MutableQuery<T : Any>(
        override val key: List<Any>,
        private val provider: suspend (List<Any>) -> T,
        private val coroutineScope: CoroutineScope,
    ) : Query<T>() {
        override var status: QueryStatus = QueryStatus.IDLE
        override var data: T? = null
        override var error: Throwable? = null

        override val isLoading: Boolean
            get() = status == QueryStatus.LOADING

        val lock = Mutex()

        private var job: Job? = null

        fun cancel() {
            job?.cancel()
            job = null
        }

        suspend fun load() {
            val job = coroutineScope.launch {
                status = QueryStatus.LOADING

                try {
                    data = provider(key)
                    status = QueryStatus.SUCCESS
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    error = e
                    status = QueryStatus.ERROR
                }
            }

            this.job = job

            job.join()
        }
    }

    private class MutableInfiniteQuery<T : Any, C : Any>(
        override val key: List<Any>,
        override val start: C,
        private val provider: suspend (List<Any>, C) -> Page<T, C>,
        private val coroutineScope: CoroutineScope,
    ) : InfiniteQuery<T, C>() {
        override var status: QueryStatus = QueryStatus.IDLE
        override var pages: MutableList<Page<T, C>> = mutableListOf()
        override var error: Throwable? = null

        override val isLoading: Boolean
            get() = status == QueryStatus.LOADING

        val lock = Mutex()

        private var jobs = mutableListOf<Job>()

        fun cancel() {
            jobs.forEach { it.cancel() }
            jobs.clear()
        }

        suspend fun load() {
            val job = launch {
                val page = provider(key, start)
                pages.add(page)
            }

            job.join()
        }

        override fun next() {
            val last = pages.lastOrNull()
            if (last == null) {
                return
            }

            val cursor = last.next
            if (cursor == null) {
                return
            }

            launch {
                lock.withLock {
                    val page = provider(key, cursor)
                    pages.add(page)
                }
            }
        }

        override fun reload() {
            launch {
                lock.withLock {
                    for (idx in pages.indices) {
                        val cursor = pages[idx].cursor
                        pages[idx] = provider(key, cursor)
                    }
                }
            }
        }

        override fun reload(cursor: C) {
            launch {
                lock.withLock {
                    val index = pages.indexOfFirst { it.cursor == cursor }
                    if (index > -1) {
                        pages[index] = provider(key, cursor)
                    }
                }
            }
        }

        private fun launch(block: suspend () -> Unit): Job {
            val job = coroutineScope.launch {
                status = QueryStatus.LOADING

                try {
                    block()

                    status = QueryStatus.SUCCESS
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    error = e
                    status = QueryStatus.ERROR
                }
            }

            jobs.add(job)

            job.invokeOnCompletion { jobs.remove(job) }

            return job
        }
    }
}
