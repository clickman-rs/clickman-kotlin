package com.lockvoid.clickman

import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * Sends the queue one take at a time: a batch of events or a visitor link. A take
 * is due at [flushAt] waiting rows, at an oldest row of [flushInterval] or when
 * forced; after a failure nothing is sent until its backoff has passed. Failures
 * are counted in memory. Runs on the client's single worker thread only.
 */
internal class Delivery(
    private val queue: Queue,
    private val sender: Sender,
    private val flushAt: Int,
    private val flushInterval: Duration,
    private val clock: () -> Long,
    private val random: Random,
) {
    private sealed interface Failures {
        data object None : Failures

        class Consecutive(val count: Int, val nextAttemptAt: Long) : Failures
    }

    private var failures: Failures = Failures.None

    /** Sends takes until nothing is due or a send fails. */
    fun drain(force: Boolean) {
        while (true) {
            if (!sendNextTake(force)) return
        }
    }

    /** True when a take left the queue. */
    private fun sendNextTake(force: Boolean): Boolean {
        val now = clock()
        return when (val take = take(now, force)) {
            null -> false
            is Take.Events -> send(take.rows, now)
            is Take.Linked -> send(take)
        }
    }

    private fun backingOff(now: Long): Boolean = when (val current = failures) {
        Failures.None -> false
        is Failures.Consecutive -> now < current.nextAttemptAt
    }

    private fun take(now: Long, force: Boolean): Take? = queue.transaction { tx ->
        tx.purge(trackedBefore = now - MAX_AGE.inWholeMilliseconds)
        if (!backingOff(now) && (force || due(tx.backlog(), now))) Take.of(tx.oldest(Batch.MAX_EVENTS)) else null
    }

    private fun due(backlog: Backlog?, now: Long): Boolean =
        backlog != null && (backlog.count >= flushAt || now - backlog.oldest >= flushInterval.inWholeMilliseconds)

    private fun send(batch: List<QueuedEvent>, now: Long): Boolean {
        val response = sender.post(Batch.encode(sentAt = now, batch.map { it.body }))
        return when (Outcome.of(response.status)) {
            Outcome.DELIVERED -> settle(batch.last())
            Outcome.REFUSED -> settle(batch.last()).also {
                logger.warning("ClickMan dropped ${batch.size} events the server refused: ${response.detail}")
            }
            Outcome.RETRY -> retry("${batch.size} events", response)
        }
    }

    private fun send(take: Take.Linked): Boolean {
        val response = sender.postLink(Link.request(take.link))
        return when (Outcome.ofLink(response.status)) {
            Outcome.DELIVERED -> settle(take.row)
            Outcome.REFUSED -> settle(take.row).also {
                logger.warning("ClickMan dropped a visitor link the server refused: ${response.detail}")
            }
            Outcome.RETRY -> retry("a visitor link", response)
        }
    }

    /** Removes a sent take, which ends with [last]. */
    private fun settle(last: QueuedEvent): Boolean {
        queue.transaction { it.deleteThrough(last.seq) }
        failures = Failures.None
        return true
    }

    private fun retry(what: String, response: Response): Boolean {
        val count = minOf(consecutiveFailures(), Int.MAX_VALUE - 1) + 1
        val delay = Backoff.delay(count, response.retryAfter, random)
        failures = Failures.Consecutive(count, Backoff.deadline(clock(), delay))
        logger.warning("ClickMan retries $what in $delay: ${response.detail}")
        return false
    }

    private fun consecutiveFailures(): Int = when (val current = failures) {
        Failures.None -> 0
        is Failures.Consecutive -> current.count
    }

    private companion object {
        val MAX_AGE = 30.days
    }
}
