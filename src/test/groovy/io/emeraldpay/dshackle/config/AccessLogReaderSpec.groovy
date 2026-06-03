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

    def "body flags default false when omitted"() {
        when:
        def act = reader.read(reader.readNode("""
accessLog:
  enabled: true
""".stripIndent()))

        then:
        !act.includeRequestBodies
        !act.includeResponseBodies
    }

    def "parses include-request-bodies only"() {
        when:
        def act = reader.read(reader.readNode("""
accessLog:
  enabled: true
  include-request-bodies: true
""".stripIndent()))

        then:
        act.includeRequestBodies
        !act.includeResponseBodies
    }

    def "parses include-response-bodies only"() {
        when:
        def act = reader.read(reader.readNode("""
accessLog:
  enabled: true
  include-response-bodies: true
""".stripIndent()))

        then:
        !act.includeRequestBodies
        act.includeResponseBodies
    }

    def "include-messages sets both body flags"() {
        when:
        def act = reader.read(reader.readNode("""
accessLog:
  enabled: true
  include-messages: true
""".stripIndent()))

        then:
        act.includeRequestBodies
        act.includeResponseBodies
    }

    def "granular key overrides include-messages legacy default"() {
        when:
        def act = reader.read(reader.readNode("""
accessLog:
  enabled: true
  include-messages: true
  include-response-bodies: false
""".stripIndent()))

        then:
        act.includeRequestBodies
        !act.includeResponseBodies
    }
}
