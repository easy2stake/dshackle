package io.emeraldpay.dshackle.config

import io.emeraldpay.dshackle.Chain

class AccessLogConfig(
    val enabled: Boolean = false,
    val includeMessages: Boolean = false,
    /**
     * `null` — `chains` not set in config; log all chains.
     * Non-null — only listed chains are logged (`emptySet()` means log nothing).
     */
    val chains: Set<Chain>? = null,
) {

    var filename: String = "./access_log.jsonl"

    fun shouldLog(chain: Chain): Boolean {
        val filter = chains ?: return true
        return chain in filter
    }

    companion object {

        fun default(): AccessLogConfig {
            return disabled()
        }

        fun disabled(): AccessLogConfig {
            return AccessLogConfig(
                enabled = false,
            )
        }
    }
}
