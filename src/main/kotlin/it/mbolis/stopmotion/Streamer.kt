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

import it.mbolis.stopmotion.Shell.Companion.open
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter.ISO_LOCAL_TIME
import javax.imageio.ImageIO
import javax.swing.*
import javax.swing.text.DefaultCaret
import kotlin.concurrent.thread
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("Please specify output image file name.")
        exitProcess(1)
    }

    Streamer(args[0])
}

class Streamer(private val imagePath: String) : JFrame() {
    private val statusArea = JTextArea(7, 30)

    private val pollingStartIcon = ImageIO.read(ClassLoader.getSystemResourceAsStream("polling-start.png"))
    private val pollingStartButton = JButton(ImageIcon(pollingStartIcon))

    private val pollingStopIcon = ImageIO.read(ClassLoader.getSystemResourceAsStream("polling-stop.png"))
    private val pollingStopButton = JButton(ImageIcon(pollingStopIcon))

    private val captureIcon = ImageIO.read(ClassLoader.getSystemResourceAsStream("capture.png"))
    private val captureButton = JButton(ImageIcon(captureIcon))

    init {
        title = "Stopmotion Streamer -> $imagePath"
        defaultCloseOperation = EXIT_ON_CLOSE

        layout = BorderLayout()

        addScrollingStatusArea()
        addControlButtons()
        pack()

        isAlwaysOnTop = true
        isVisible = true
    }

    private fun addScrollingStatusArea() {
        statusArea.isEditable = false
        statusArea.caret.apply {
            if (this is DefaultCaret) {
                updatePolicy = DefaultCaret.ALWAYS_UPDATE
            }
        }

        add(JScrollPane(statusArea, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER),
                BorderLayout.CENTER)
    }

    private fun addControlButtons() {
        val controlButtons = JPanel(FlowLayout(FlowLayout.CENTER))

        pollingStartButton.addActionListener(listen(::startPolling))
        controlButtons.add(pollingStartButton)

        pollingStopButton.isEnabled = false
        pollingStopButton.addActionListener(listen(::stopPolling))
        controlButtons.add(pollingStopButton)

        captureButton.addActionListener(listen(::capture))
        controlButtons.add(captureButton)

        add(controlButtons, BorderLayout.SOUTH)
    }

    private fun <T> listen(listener: () -> Unit): (T) -> Unit = { listener() }

    private fun pollPreview() {
        val time = thread { Thread.sleep(250) }

        open(imagePath) {
            when {
                isReady -> {
                    val result = send("capture-preview")
                    when (result) {
                        is Result.Error -> {
                            SwingUtilities.invokeLater {
                                pollingStartButton.isEnabled = true
                                pollingStopButton.isEnabled = false

                                appendStatus(result.message)
                            }

                            reset()
                        }

                        is Result.Success -> {
                            time.join()
                            pollPreview()
                        }
                    }
                }


                isInterrupted -> {
                    SwingUtilities.invokeLater {
                        pollingStartButton.isEnabled = true
                        pollingStopButton.isEnabled = false
                    }
                }
            }
        }
    }

    private fun startPolling() {
        pollingStartButton.isEnabled = false
        pollingStopButton.isEnabled = false

        open(imagePath) {
            when {
                isReady -> {
                    SwingUtilities.invokeLater {
                        pollingStartButton.isEnabled = false
                        pollingStopButton.isEnabled = true
                    }

                    pollPreview()
                }

                isFailed -> {
                    val errorMessage = lastError
                    SwingUtilities.invokeLater {
                        pollingStartButton.isEnabled = true
                        pollingStopButton.isEnabled = false

                        appendStatus(errorMessage)
                    }

                    close()
                }
            }
        }
    }

    private fun stopPolling() {
        open(imagePath) {
            if (!isReady) return@open

            interrupt()
        }
    }

    private fun capture() {
        pollingStartButton.isEnabled = false
        pollingStopButton.isEnabled = false

        open(imagePath) {
            if (!isReady) return@open

            val result = send("capture-image-and-download")
            when (result) {
                is Result.Error -> {
                    SwingUtilities.invokeLater {
                        appendStatus(lastError)
                    }

                    reset()
                }

                is Result.Success -> {
                    SwingUtilities.invokeLater {
                        appendStatus(result.text)
                    }
                }
            }

            SwingUtilities.invokeLater {
                pollingStartButton.isEnabled = true
                pollingStopButton.isEnabled = false
            }

            interrupt()
        }
    }

    private fun appendStatus(message: String?) {
        statusArea.append(formattedOutput(message))
    }
}

private fun formattedOutput(message: String?) = "${formattedTime()} $message\n"

private fun formattedTime() = LocalDateTime.now().format(ISO_LOCAL_TIME)
