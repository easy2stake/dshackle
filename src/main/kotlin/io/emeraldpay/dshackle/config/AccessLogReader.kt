package io.emeraldpay.dshackle.config

import io.emeraldpay.dshackle.Chain
import io.emeraldpay.dshackle.Global
import io.emeraldpay.dshackle.foundation.YamlConfigReader
import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.nodes.MappingNode

class AccessLogReader : YamlConfigReader<AccessLogConfig>() {

    companion object {
        private val log = LoggerFactory.getLogger(AccessLogReader::class.java)
    }

    override fun read(input: MappingNode?): AccessLogConfig {
        return getMapping(input, "accessLog")?.let { node ->
            val enabled = getValueAsBool(node, "enabled") ?: false
            if (!enabled) {
                AccessLogConfig.disabled()
            } else {
                val legacyIncludeMessages = getValueAsBool(node, "include-messages")
                val includeRequestBodies = getValueAsBool(node, "include-request-bodies")
                    ?: legacyIncludeMessages ?: false
                val includeResponseBodies = getValueAsBool(node, "include-response-bodies")
                    ?: legacyIncludeMessages ?: false
                val chains = readChains(node)
                val minLatencyMs = readMinLatencyMs(node)
                val config = AccessLogConfig(
                    true,
                    includeRequestBodies,
                    includeResponseBodies,
                    chains,
                    minLatencyMs,
                )
                getValueAsString(node, "filename")?.let {
                    config.filename = it
                }
                config
            }
        } ?: AccessLogConfig.default()
    }

    private fun readChains(node: MappingNode): Set<Chain>? {
        if (!hasAny(node, "chains")) {
            return null
        }
        val list = getListOfString(node, "chains") ?: emptyList()
        val chains = HashSet<Chain>()
        list.forEach { id ->
            val chain = Global.chainById(id)
            if (chain == Chain.UNSPECIFIED) {
                log.warn("Invalid accessLog chain: $id")
            } else {
                chains.add(chain)
            }
        }
        if (chains.isEmpty()) {
            log.error(
                "accessLog.chains is set but contains no valid chains; " +
                    "access log will not record requests (fix chain ids or remove chains to log all)",
            )
        }
        return chains
    }

    private fun readMinLatencyMs(node: MappingNode): Long? {
        val value = getValueAsLong(node, "min-latency-ms") ?: return null
        if (value < 0) {
            log.warn("accessLog.min-latency-ms must be non-negative; latency filter disabled")
            return null
        }
        return value
    }
}
