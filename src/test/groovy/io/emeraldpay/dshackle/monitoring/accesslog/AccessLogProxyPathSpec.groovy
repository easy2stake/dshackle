package io.emeraldpay.dshackle.monitoring.accesslog

import io.emeraldpay.dshackle.Chain
import io.emeraldpay.dshackle.Global
import io.emeraldpay.dshackle.config.AccessLogConfig
import io.emeraldpay.dshackle.config.MainConfig
import io.emeraldpay.dshackle.config.ProxyConfig
import io.emeraldpay.dshackle.proxy.HttpHandler
import io.emeraldpay.dshackle.proxy.ProxyServer
import io.emeraldpay.dshackle.proxy.ReadRpcJson
import io.emeraldpay.dshackle.proxy.WriteRpcJson
import io.emeraldpay.dshackle.rpc.NativeCall
import io.netty.buffer.Unpooled
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.netty.DisposableServer
import reactor.netty.http.client.HttpClient
import reactor.netty.http.server.HttpServer
import spock.lang.Specification

import java.time.Duration

/**
 * End-to-end path: HTTP proxy -> AccessHandlerHttp -> AccessLogWriter, with min-latency-ms filtering.
 */
class AccessLogProxyPathSpec extends Specification {

    private static final String RPC_BODY = '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}'
    private static final long MIN_LATENCY_MS = 500L
    private static final long SLOW_REPLY_DELAY_MS = 600L

    def "fast proxy NativeCall is omitted from access log when min-latency-ms is set"() {
        setup:
        def ctx = startProxy(0L)

        when:
        postRpc(ctx.port)
        ctx.writer.flush()

        then:
        ctx.accessLog.exists()
        ctx.accessLog.readLines().isEmpty()

        cleanup:
        ctx.server.disposeNow()
        EventsBuilder.accessLogConfig = ctx.previousAccessLogConfig
    }

    def "slow proxy NativeCall is written to access log when min-latency-ms is set"() {
        setup:
        def ctx = startProxy(SLOW_REPLY_DELAY_MS)

        when:
        postRpc(ctx.port)
        ctx.writer.flush()

        then:
        def lines = ctx.accessLog.readLines()
        lines.size() == 1
        def json = Global.objectMapper.readValue(lines[0], Map)
        json["method"] == "NativeCall"
        json["channel"] == "JSONRPC"
        (json["latency"] as Number).longValue() >= MIN_LATENCY_MS

        cleanup:
        ctx.server.disposeNow()
        EventsBuilder.accessLogConfig = ctx.previousAccessLogConfig
    }

    private void postRpc(int port) {
        HttpClient.create()
                .post()
                .uri("http://127.0.0.1:${port}/eth")
                .send(Mono.just(Unpooled.wrappedBuffer(RPC_BODY.bytes)))
                .responseContent()
                .aggregate()
                .asByteArray()
                .block(Duration.ofSeconds(10))
    }

    private Map startProxy(long responseDelayMs) {
        File accessLog = File.createTempFile("accesslog-proxy-", ".jsonl")
        MainConfig mainConfig = new MainConfig()
        mainConfig.accessLogConfig = new AccessLogConfig(true, false, false, null, MIN_LATENCY_MS).tap {
            it.filename = accessLog.absolutePath
        }
        AccessLogWriter writer = new AccessLogWriter(mainConfig)
        def previousAccessLogConfig = EventsBuilder.accessLogConfig
        EventsBuilder.accessLogConfig = mainConfig.accessLogConfig

        NativeCall nativeCall = Mock(NativeCall) {
            nativeCallResult(_) >> {
                // ReadRpcJson assigns internal call id 0 for the first item in a single request
                def result = new NativeCall.CallResult(
                        0, 0L, '"0x0"'.bytes, null, null, [], null
                )
                def flux = Flux.just(result)
                responseDelayMs > 0 ? flux.delayElements(Duration.ofMillis(responseDelayMs)) : flux
            }
        }

        def route = new ProxyConfig.Route("eth", Chain.ETHEREUM__MAINNET)
        HttpHandler httpHandler = new HttpHandler(
                new ProxyConfig(),
                new ReadRpcJson(),
                new WriteRpcJson(),
                nativeCall,
                new AccessHandlerHttp(mainConfig, writer).factory,
                Stub(ProxyServer.RequestMetricsFactory),
        )

        DisposableServer server = HttpServer.create()
                .port(0)
                .handle { req, resp ->
                    httpHandler.proxy(route).apply(req, resp)
                }
                .bindNow()

        return [
                accessLog             : accessLog,
                writer                : writer,
                server                : server,
                port                  : server.port(),
                previousAccessLogConfig: previousAccessLogConfig,
        ]
    }
}
