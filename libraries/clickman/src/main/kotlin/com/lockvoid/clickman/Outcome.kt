package com.lockvoid.clickman

/** What an answer does to its batch (outcomes.json); status 0 is no answer at all. */
internal enum class Outcome {
    DELIVERED,
    REFUSED,
    RETRY;

    companion object {
        fun of(status: Int): Outcome = when (status) {
            in 200..299 -> DELIVERED
            400, 413, 415 -> REFUSED
            else -> RETRY
        }

        /** What an answer does to a link (identity.json): as to a batch, except that 422, a visitor linked to another account, refuses it. */
        fun ofLink(status: Int): Outcome = if (status == 422) REFUSED else of(status)
    }
}
