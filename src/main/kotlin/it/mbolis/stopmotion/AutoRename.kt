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

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption.*
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import kotlin.concurrent.thread

class AutoRename(filePath: Path) {
    constructor(fileName: String) : this(Paths.get(fileName))

    private val basedir = filePath.parent
    private val filename = filePath.fileName.toString()

    private val watcher = filePath.fileSystem.newWatchService()
    private val watchKey = basedir.register(watcher, ENTRY_CREATE)

    private val pollThread = thread {
        var run = true
        while (run) {
            if (Thread.interrupted()) return@thread

            try {
                watcher.take()
            } catch (e: InterruptedException) {
                return@thread
            }.apply {
                pollEvents()
                        .filter { it.kind() == ENTRY_CREATE }
                        .map { it.context() as Path }
                        .filter { it.fileName.toString() == "thumb_$filename" }
                        .forEach { Files.move(basedir.resolve(it), filePath, ATOMIC_MOVE, REPLACE_EXISTING) }

                run = reset()
            }
        }
    }

    fun close() {
        pollThread.interrupt()
        watchKey.cancel()
    }
}