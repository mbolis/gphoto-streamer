/*
 * Copyright © 2019 Marco Bolis
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 *  documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 *  rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 *  permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all copies or substantial portions of the
 *  Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 *  WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS
 *  OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 *  OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package it.mbolis.stopmotion

import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

class Promise<V>(private val completableFuture: CompletableFuture<V>) {

    fun get(timeout: Long = Long.MAX_VALUE / 2): V = completableFuture.get(timeout, TimeUnit.NANOSECONDS)

    companion object {

        fun <R> supplyAsync(executor: Executor = ForkJoinPool.commonPool(), supplier: () -> R): Promise<R> =
                Promise(CompletableFuture.supplyAsync(Supplier(supplier), executor))

        fun <V> anyOf(vararg promises: Promise<out V>): Promise<V> {
            val completableFutures = promises.map { it.completableFuture }.toTypedArray()
            return Promise(CompletableFuture.anyOf(*completableFutures) as CompletableFuture<V>)
        }
    }
}