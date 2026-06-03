package io.emeraldpay.dshackle.config

import io.emeraldpay.dshackle.Chain
import spock.lang.Specification

class AccessLogReaderSpec extends Specification {

    AccessLogReader reader = new AccessLogReader()

    def "chains omitted means log all chains"() {
        when:
        def act = reader.read(reader.readNode("""
accessLog:
  enabled: true
""".stripIndent()))

        then:
        act.enabled
        act.chains == null
    }

    def "invalid chains does not fall back to log all"() {
        when:
        def act = reader.read(reader.readNode("""
accessLog:
  enabled: true
  chains:
    - not-a-real-chain
""".stripIndent()))

        then:
        act.enabled
        act.chains != null
        act.chains.isEmpty()
    }

    def "parses valid chains"() {
        when:
        def act = reader.read(reader.readNode("""
accessLog:
  enabled: true
  chains:
    - ethereum
    - bitcoin
""".stripIndent()))

        then:
        act.chains == [Chain.ETHEREUM__MAINNET, Chain.BITCOIN__MAINNET] as Set
    }

    def "min-latency-ms omitted means no latency filter"() {
        when:
        def act = reader.read(reader.readNode("""
accessLog:
  enabled: true
""".stripIndent()))

        then:
        act.minLatencyMs == null
    }

    def "parses min-latency-ms"() {
        when:
        def act = reader.read(reader.readNode("""
accessLog:
  enabled: true
  min-latency-ms: 500
""".stripIndent()))

        then:
        act.minLatencyMs == 500L
    }

    def "negative min-latency-ms disables filter"() {
        when:
        def act = reader.read(reader.readNode("""
accessLog:
  enabled: true
  min-latency-ms: -1
""".stripIndent()))

        then:
        act.minLatencyMs == null
    }
}
