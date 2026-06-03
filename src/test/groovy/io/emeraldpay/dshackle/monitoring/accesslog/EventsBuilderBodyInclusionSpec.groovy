package io.emeraldpay.dshackle.monitoring.accesslog

import io.emeraldpay.api.proto.BlockchainOuterClass
import io.emeraldpay.api.proto.Common
import io.emeraldpay.dshackle.Chain
import io.emeraldpay.dshackle.config.AccessLogConfig
import io.emeraldpay.dshackle.rpc.NativeCall
import spock.lang.Specification

import java.time.Instant

class EventsBuilderBodyInclusionSpec extends Specification {

    private AccessLogConfig previousConfig

    def setup() {
        previousConfig = EventsBuilder.accessLogConfig
    }

    def cleanup() {
        EventsBuilder.accessLogConfig = previousConfig
    }

    def "includes request params only when includeRequestBodies is true"() {
        given:
        EventsBuilder.accessLogConfig = new AccessLogConfig(true, true, false)
        def event = buildNativeCallEvent('["0x1"]', '{}'.bytes, null)

        expect:
        event.nativeCall.requestParams == '["0x1"]'
        event.responseBody == null
        event.errorMessage == null
    }

    def "includes response body only when includeResponseBodies is true"() {
        given:
        EventsBuilder.accessLogConfig = new AccessLogConfig(true, false, true)
        def event = buildNativeCallEvent('[]', '{"result":"0x1"}'.bytes, null)

        expect:
        event.nativeCall.requestParams == null
        event.responseBody == '{"result":"0x1"}'
        event.errorMessage == ""
    }

    def "includes error message with includeResponseBodies"() {
        given:
        EventsBuilder.accessLogConfig = new AccessLogConfig(true, false, true)
        def error = new NativeCall.CallError(1, "upstream failed", null, null, [])
        def event = buildNativeCallEvent('[]', null, error)

        expect:
        event.nativeCall.requestParams == null
        event.responseBody == ""
        event.errorMessage == "upstream failed"
    }

    def "omits all bodies when both flags are false"() {
        given:
        EventsBuilder.accessLogConfig = new AccessLogConfig(true, false, false)
        def event = buildNativeCallEvent('[]', '{}'.bytes, null)

        expect:
        event.nativeCall.requestParams == null
        event.responseBody == null
        event.errorMessage == null
    }

    private static Events.NativeCall buildNativeCallEvent(
            String requestParams,
            byte[] responseBytes,
            NativeCall.CallError error
    ) {
        def requestStart = Instant.ofEpochMilli(1626746880000L)
        def replyTs = Instant.ofEpochMilli(1626746880123L)
        def builder = new EventsBuilder.NativeCall(requestStart)
        builder.withChain(Chain.ETHEREUM__MAINNET.id)
        def request = BlockchainOuterClass.NativeCallRequest.newBuilder()
                .setChain(Common.ChainRef.forNumber(Chain.ETHEREUM__MAINNET.id))
                .addItems(BlockchainOuterClass.NativeCallItem.newBuilder()
                        .setMethod("eth_getBlockByNumber")
                        .setId(1)
                        .setPayload(com.google.protobuf.ByteString.copyFromUtf8(requestParams))
                        .setNonce(0)
                        .build())
                .build()
        builder.onRequest(request)
        def callResult = new NativeCall.CallResult(1, 0L, responseBytes, error, null, [], null)
        return builder.onReply(callResult, Events.Channel.JSONRPC, replyTs)
    }
}
