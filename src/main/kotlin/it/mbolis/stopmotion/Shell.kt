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

import it.mbolis.stopmotion.Promise.Companion.anyOf
import it.mbolis.stopmotion.Promise.Companion.supplyAsync
import java.io.Reader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors.newFixedThreadPool
import java.util.concurrent.Executors.newSingleThreadExecutor
import kotlin.concurrent.thread

sealed class Result {
    object Ready : Result()
    data class Success(val text: String) : Result()
    data class Error(val message: String? = null) : Result()
    object Interrupted : Result()
    object Closed : Result()
}

typealias ShellCallback = Shell.(Result) -> Unit

private val promptRe = """gphoto2: \{.*} .*>\s*$""".toRegex()
private val errorRe = """\*+\s*(Error.*?)\s*\*+""".toRegex()

class Shell private constructor(private val imagePath: String) {
    private val process = gphoto2("--shell", "--force-overwrite", "--filename", imagePath).start()

    private val stdin = process.outputStream.bufferedWriter()
    private val stdout = process.inputStream.bufferedReader()
    private val stderr = process.errorStream.bufferedReader()

    private var lastResult: Result = Result.Ready

    val isClosed: Boolean
        get() = lastResult is Result.Closed

    val isReady: Boolean
        get() = lastResult is Result.Ready || lastResult is Result.Success

    val isFailed: Boolean
        get() = lastResult is Result.Error

    val lastError: String?
        get() = lastResult.let {
            if (it is Result.Error)
                it.message
            else
                null
        }

    val isInterrupted: Boolean
        get() = lastResult is Result.Interrupted

    fun reset() {
        if (lastResult is Result.Closed) return

        lastResult = Result.Ready
    }

    fun interrupt() {
        if (lastResult is Result.Closed) return

        lastResult = Result.Interrupted
    }

    private fun readUntilPrompt(timeout: Long = Long.MAX_VALUE / 2): String? {
        val dropStderr = thread {
            while (true) {
                stderr.readBurst()
            }
        }

        return stdout.readBurst(timeout)?.let {
            if (it.contains(promptRe)) {
                it.replaceFirst(promptRe, "")
            } else {
                it + readUntilPrompt()
            }
        }.also {
            dropStderr.interrupt()
        }
    }

    fun send(command: String): Result {
        if (lastResult is Result.Closed) return Result.Closed

        stdin.also {
            it.appendln(command)
            it.flush()
        }
        val out = readUntilPrompt()?.trim() ?: return Result.Error()
        val cleanOut = out.replace("""^\s*($command[\s\n]*)*""".toRegex(), "")
        return if (out.contains(errorRe)) {
            Result.Error(cleanOut.replace(errorRe, "$1"))
        } else {
            Result.Success(cleanOut)
        }.also {
            lastResult = it
        }
    }

    fun close() {
        if (lastResult is Result.Closed) return

        lastResult = Result.Closed
        close(imagePath)
        process.destroyForcibly()
    }

    private class ShellExecution private constructor(private val shell: Shell) {
        private val executor = newSingleThreadExecutor()

        fun append(actions: ShellCallback) {
            executor.execute {
                shell.apply { actions(lastResult) }
            }
        }

        fun close() {
            executor.shutdown()
        }

        companion object {
            fun start(shell: Shell): ShellExecution = ShellExecution(shell).apply {
                executor.execute {
                    shell.lastResult = bootstrapRace(shell)
                }
            }

            private fun bootstrapRace(shell: Shell, executor: ExecutorService = newFixedThreadPool(2)): Result = anyOf(
                    supplyAsync(executor) { Result.Error(shell.stderr.readBurst()?.trim()?.replace(errorRe, "$1")) },
                    supplyAsync(executor) {
                        val out = shell.readUntilPrompt(3000)
                        if (out != null) {
                            Result.Success(out)
                        } else {
                            Result.Error()
                        }
                    })
                    .get().also {
                        executor.shutdownNow()
                    }
        }
    }

    companion object {
        private val openShells = mutableMapOf<String, ShellExecution>()

        fun open(imagePath: String, actions: ShellCallback) {
            openShells
                    .computeIfAbsent(imagePath) {
                        ShellExecution.start(Shell(it))
                    }
                    .append(actions)
        }

        private fun close(imagePath: String) {
            openShells[imagePath]?.close()
            openShells.remove(imagePath)
        }
    }
}

private fun gphoto2(vararg cmd: String): ProcessBuilder =
        ProcessBuilder("gphoto2", *cmd).environment(mapOf("LANG" to "C"))

private fun ProcessBuilder.environment(env: Map<String, String>): ProcessBuilder =
        apply {
            environment().apply { putAll(env) }
        }

private fun Reader.readBurst(timeout: Long = kotlin.Long.MAX_VALUE / 2): String? {
    val endTime = System.currentTimeMillis() + timeout
    while (!ready()) {
        if (Thread.interrupted()) return null

        try {
            Thread.sleep(50)
        } catch (e: InterruptedException) {
            return null
        }

        if (System.currentTimeMillis() >= endTime) {
            return null
        }
    }

    val out = StringBuilder()
    val buffer = CharArray(256)
    while (ready()) {
        val length = read(buffer)
        out.append(buffer, 0, length)
    }

    return out.toString()
}