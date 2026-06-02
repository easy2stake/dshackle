package io.emeraldpay.dshackle.monitoring.accesslog

import io.emeraldpay.api.proto.BlockchainOuterClass
import io.emeraldpay.api.proto.Common
import io.emeraldpay.dshackle.Chain
import io.emeraldpay.dshackle.rpc.NativeCall
import spock.lang.Specification

import java.time.Instant

class EventsBuilderNativeCallSpec extends Specification {

    def "gRPC onReply latency is non-negative elapsed from request start"() {
        setup:
        def requestStart = Instant.ofEpochMilli(1_000)
        def builder = new EventsBuilder.NativeCall(requestStart)
        builder.withChain(Chain.ETHEREUM__MAINNET.id)
        def request = BlockchainOuterClass.NativeCallRequest.newBuilder()
                .setChain(Common.ChainRef.forNumber(Chain.ETHEREUM__MAINNET.id))
                .addItems(BlockchainOuterClass.NativeCallItem.newBuilder()
                        .setMethod("eth_blockNumber")
                        .setId(1)
                        .setPayload(com.google.protobuf.ByteString.EMPTY)
                        .setNonce(0)
                        .build())
                .build()
        builder.onRequest(request)
        def reply = BlockchainOuterClass.NativeCallReplyItem.newBuilder()
                .setId(1)
                .setSucceed(true)
                .setPayload(com.google.protobuf.ByteString.EMPTY)
                .build()

        when:
        def event = builder.onReply(reply)

        then:
        event.request.start == requestStart
        event.latency >= 0
    }

    def "HTTP onReply latency uses arrival to per-reply timestamp"() {
        setup:
        def arrival = Instant.ofEpochMilli(5_000)
        def replyTs = Instant.ofEpochMilli(5_420)
        def builder = new EventsBuilder.NativeCall(arrival)
        builder.withChain(Chain.ETHEREUM__MAINNET.id)
        def request = BlockchainOuterClass.NativeCallRequest.newBuilder()
                .setChain(Common.ChainRef.forNumber(Chain.ETHEREUM__MAINNET.id))
                .addItems(BlockchainOuterClass.NativeCallItem.newBuilder()
                        .setMethod("eth_getBalance")
                        .setId(7)
                        .setPayload(com.google.protobuf.ByteString.EMPTY)
                        .setNonce(0)
                        .build())
                .build()
        builder.onRequest(request)
        def callResult = new NativeCall.CallResult(7, 0L, "{}".bytes, null, null, [], null)

        when:
        def event = builder.onReply(callResult, Events.Channel.JSONRPC, replyTs)

        then:
        event.request.start == arrival
        event.latency == 420
    }

    def "batch replies can have different latency and ts"() {
        setup:
        def arrival = Instant.ofEpochMilli(10_000)
        def builder = new EventsBuilder.NativeCall(arrival)
        builder.withChain(Chain.ETHEREUM__MAINNET.id)
        def item1 = BlockchainOuterClass.NativeCallItem.newBuilder()
                .setMethod("eth_blockNumber")
                .setId(1)
                .setPayload(com.google.protobuf.ByteString.EMPTY)
                .setNonce(0)
                .build()
        def item2 = BlockchainOuterClass.NativeCallItem.newBuilder()
                .setMethod("eth_chainId")
                .setId(2)
                .setPayload(com.google.protobuf.ByteString.EMPTY)
                .setNonce(0)
                .build()
        def request = BlockchainOuterClass.NativeCallRequest.newBuilder()
                .setChain(Common.ChainRef.forNumber(Chain.ETHEREUM__MAINNET.id))
                .addAllItems([item1, item2])
                .build()
        builder.onRequest(request)
        def first = new NativeCall.CallResult(1, 0L, "a".bytes, null, null, [], null)
        def second = new NativeCall.CallResult(2, 0L, "b".bytes, null, null, [], null)
        def firstTs = Instant.ofEpochMilli(10_100)
        def secondTs = Instant.ofEpochMilli(10_350)

        when:
        def e1 = builder.onReply(first, Events.Channel.JSONRPC, firstTs)
        def e2 = builder.onReply(second, Events.Channel.JSONRPC, secondTs)

        then:
        e1.latency == 100
        e2.latency == 350
    }
}
