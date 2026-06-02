package io.emeraldpay.dshackle.monitoring.accesslog


import io.emeraldpay.dshackle.Chain
import io.emeraldpay.dshackle.Global
import io.emeraldpay.dshackle.config.AccessLogConfig
import io.emeraldpay.dshackle.config.MainConfig
import spock.lang.Specification

import java.time.Instant

class AccessLogWriterSpec extends Specification {

    def "writes log event"() {
        setup:
        File dir = File.createTempDir("dshackle-test-")
        File accessLog = new File(dir, "accesslog.jsonl")
        println("Write access log to $accessLog.absolutePath")
        MainConfig config = new MainConfig()
        config.accessLogConfig = new AccessLogConfig(true, false).tap {
            it.filename = accessLog.absolutePath
        }
        AccessLogWriter logWriter = new AccessLogWriter(config)

        when:
        def event = new Events.Status(
                Chain.ETHEREUM__MAINNET, UUID.fromString("9d8ecbf3-12fb-49cf-af9d-949a1050a000"),
                new Events.StreamRequestDetails(
                        UUID.fromString("9d8ecbf3-12fb-49cf-af9d-949a1050a000"),
                        Instant.ofEpochMilli(1626746880123),
                        new Events.Remote(
                                ["127.0.0.1", "172.217.8.78"], "172.217.8.78", "UnitTest"
                        )
                )
        )
        logWriter.submit([event])
        logWriter.flush()
        def act = accessLog.readLines()
        then:
        act.size() == 1
        with(act[0]) {
            def json = Global.objectMapper.readValue(it, Map)
            json["version"] == "accesslog/v1beta"
            json["id"] == "9d8ecbf3-12fb-49cf-af9d-949a1050a000"
            json["method"] == "Status"
            json["blockchain"] == "ETHEREUM__MAINNET"
            json["request"]["start"] == "2021-07-20T02:08:00.123Z"
            json["request"]["id"] == "9d8ecbf3-12fb-49cf-af9d-949a1050a000"
            json["request"]["remote"]["ip"] == "172.217.8.78"
            json["request"]["remote"]["userAgent"] == "UnitTest"
        }
    }

    def "skips events for chains not in filter"() {
        setup:
        File dir = File.createTempDir("dshackle-test-")
        File accessLog = new File(dir, "accesslog-filtered.jsonl")
        MainConfig config = new MainConfig()
        config.accessLogConfig = new AccessLogConfig(true, false, [Chain.BITCOIN__MAINNET] as Set).tap {
            it.filename = accessLog.absolutePath
        }
        AccessLogWriter logWriter = new AccessLogWriter(config)
        def event = new Events.Status(
                Chain.ETHEREUM__MAINNET, UUID.fromString("9d8ecbf3-12fb-49cf-af9d-949a1050a000"),
                new Events.StreamRequestDetails(
                        UUID.fromString("9d8ecbf3-12fb-49cf-af9d-949a1050a000"),
                        Instant.ofEpochMilli(1626746880123),
                        new Events.Remote(
                                ["127.0.0.1"], "127.0.0.1", "UnitTest"
                        )
                )
        )

        when:
        logWriter.submit([event])
        logWriter.flush()

        then:
        accessLog.exists()
        accessLog.readLines().isEmpty()
    }

    def "skips all events when chain filter is explicitly empty"() {
        setup:
        File dir = File.createTempDir("dshackle-test-")
        File accessLog = new File(dir, "accesslog-empty-filter.jsonl")
        MainConfig config = new MainConfig()
        config.accessLogConfig = new AccessLogConfig(true, false, [] as Set).tap {
            it.filename = accessLog.absolutePath
        }
        AccessLogWriter logWriter = new AccessLogWriter(config)
        def event = new Events.Status(
                Chain.BITCOIN__MAINNET, UUID.fromString("9d8ecbf3-12fb-49cf-af9d-949a1050a000"),
                new Events.StreamRequestDetails(
                        UUID.fromString("9d8ecbf3-12fb-49cf-af9d-949a1050a000"),
                        Instant.ofEpochMilli(1626746880123),
                        new Events.Remote(
                                ["127.0.0.1"], "127.0.0.1", "UnitTest"
                        )
                )
        )

        when:
        logWriter.submit([event])
        logWriter.flush()

        then:
        accessLog.readLines().isEmpty()
    }

    def "writes NativeCall latency field"() {
        setup:
        File dir = File.createTempDir("dshackle-test-")
        File accessLog = new File(dir, "accesslog-latency.jsonl")
        MainConfig config = new MainConfig()
        config.accessLogConfig = new AccessLogConfig(true, false).tap {
            it.filename = accessLog.absolutePath
        }
        AccessLogWriter logWriter = new AccessLogWriter(config)
        def requestStart = Instant.ofEpochMilli(1626746880000)
        def replyTs = Instant.ofEpochMilli(1626746880123)

        when:
        def event = new Events.NativeCall(
                Chain.ETHEREUM__MAINNET,
                UUID.fromString("578d83db-cf53-4ef8-b73e-3f1cc0a67e96"),
                Events.Channel.JSONRPC,
                new Events.StreamRequestDetails(
                        UUID.fromString("513b9b49-b472-4c83-b4b7-58dd2aabe9f6"),
                        requestStart,
                        new Events.Remote(["127.0.0.1"], "127.0.0.1", "UnitTest")
                ),
                1,
                0,
                null,
                null,
                null,
                123L,
                true,
                null,
                10L,
                new Events.NativeCallItemDetails("eth_blockNumber", 1, 2L, 0L),
                null,
                null,
                null,
                null,
        )
        event.ts = replyTs
        logWriter.submit([event])
        logWriter.flush()

        then:
        def json = Global.objectMapper.readValue(accessLog.readLines()[0], Map)
        json["latency"] == 123
        json["ts"] == "2021-07-20T02:08:00.123Z"
        json["request"]["start"] == "2021-07-20T02:08:00Z"
    }
}
