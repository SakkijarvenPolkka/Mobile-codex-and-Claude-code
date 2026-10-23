// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.ops

import org.junit.rules.ExternalResource
import java.util.Collections
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * Keeps what the ops package logs during a test, instead of printing it to stderr, for the test to
 * check ([warnings]). The logger prints as before once the test is over.
 */
class CapturedLog : ExternalResource() {
    // Held here too: java.util.logging keeps its loggers only weakly.
    private val logger = Logger.getLogger("io.github.sakkijarvenpolkka.explorer.ops")
    private val records = Collections.synchronizedList(ArrayList<LogRecord>())
    private var usedParentHandlers = true

    private val handler = object : Handler() {
        override fun publish(record: LogRecord) {
            records += record
        }

        override fun flush() = Unit

        override fun close() = Unit
    }

    /** The messages of the warnings logged so far, in order. */
    val warnings: List<String>
        get() = synchronized(records) { records.filter { it.level == Level.WARNING }.map { it.message } }

    override fun before() {
        usedParentHandlers = logger.useParentHandlers
        logger.useParentHandlers = false
        logger.addHandler(handler)
    }

    override fun after() {
        logger.removeHandler(handler)
        logger.useParentHandlers = usedParentHandlers
    }
}
