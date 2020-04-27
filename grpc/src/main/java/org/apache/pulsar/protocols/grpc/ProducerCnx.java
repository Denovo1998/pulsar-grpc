/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.protocols.grpc;

import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.EventLoop;
import org.apache.bookkeeper.mledger.util.SafeRun;
import org.apache.pulsar.broker.authentication.AuthenticationDataSource;
import org.apache.pulsar.broker.service.*;
import org.apache.pulsar.protocols.grpc.api.CommandSend;
import org.apache.pulsar.protocols.grpc.api.SendResult;

import java.net.SocketAddress;
import java.nio.ByteBuffer;

public class ProducerCnx implements ServerCnx {
    private final BrokerService service;
    private final SocketAddress remoteAddress;
    private final String authRole;
    private final AuthenticationDataSource authenticationData;
    private final ServerCallStreamObserver<SendResult> responseObserver;
    private final EventLoop eventLoop;

    // Max number of pending requests per produce RPC
    private static final int MaxPendingSendRequests = 1000;
    private static final int ResumeReadsThreshold = MaxPendingSendRequests / 2;
    private int pendingSendRequest = 0;
    private int nonPersistentPendingMessages = 0;
    private final int MaxNonPersistentPendingMessages;
    private volatile boolean isAutoRead = true;
    private volatile boolean autoReadDisabledRateLimiting = false;
    private final AutoReadAwareOnReadyHandler onReadyHandler = new AutoReadAwareOnReadyHandler();

    public ProducerCnx(BrokerService service, SocketAddress remoteAddress, String authRole,
            AuthenticationDataSource authenticationData, StreamObserver<SendResult> responseObserver,
            EventLoop eventLoop) {
        this.service = service;
        this.remoteAddress = remoteAddress;
        this.MaxNonPersistentPendingMessages = service.pulsar().getConfiguration()
                .getMaxConcurrentNonPersistentMessagePerConnection();
        this.authRole = authRole;
        this.authenticationData = authenticationData;
        this.responseObserver = (ServerCallStreamObserver<SendResult>) responseObserver;
        this.responseObserver.disableAutoInboundFlowControl();
        this.responseObserver.setOnReadyHandler(onReadyHandler);
        this.eventLoop = eventLoop;
    }

    @Override
    public SocketAddress clientAddress() {
        return remoteAddress;
    }

    @Override
    public BrokerService getBrokerService() {
        return service;
    }

    @Override
    public String getRole() {
        return authRole;
    }

    @Override
    public AuthenticationDataSource getAuthenticationData() {
        return authenticationData;
    }

    @Override
    public void closeProducer(Producer producer) {
        responseObserver.onCompleted();
    }

    @Override
    public long getMessagePublishBufferSize() {
        // TODO: implement
        return Long.MAX_VALUE;
    }

    @Override
    public void cancelPublishRateLimiting() {
        // TODO: implement
    }

    @Override
    public void cancelPublishBufferLimiting() {
        // TODO: implement
    }

    @Override
    public void disableCnxAutoRead() {
        // TODO: implement
    }

    public void handleSend(CommandSend send, Producer producer) {
        ByteBuffer buffer = send.getHeadersAndPayload().asReadOnlyByteBuffer();
        ByteBuf headersAndPayload = Unpooled.wrappedBuffer(buffer);

        if (producer.isNonPersistentTopic()) {
            // avoid processing non-persist message if reached max concurrent-message limit
            if (nonPersistentPendingMessages > MaxNonPersistentPendingMessages) {
                final long sequenceId = send.getSequenceId();
                final long highestSequenceId = send.getHighestSequenceId();
                service.getTopicOrderedExecutor().executeOrdered(
                        producer.getTopic().getName(),
                        SafeRun.safeRun(() -> responseObserver.onNext(Commands.newSendReceipt(sequenceId, highestSequenceId, -1, -1)))
                );
                producer.recordMessageDrop(send.getNumMessages());
                return;
            } else {
                nonPersistentPendingMessages++;
            }
        }

        startSendOperation(producer);

        // Persist the message
        if (send.hasHighestSequenceId() && send.getSequenceId() <= send.getHighestSequenceId()) {
            producer.publishMessage(producer.getProducerId(), send.getSequenceId(), send.getHighestSequenceId(),
                    headersAndPayload, send.getNumMessages());
        } else {
            producer.publishMessage(producer.getProducerId(), send.getSequenceId(), headersAndPayload, send.getNumMessages());
        }
        onMessageHandled();
    }

    private void startSendOperation(Producer producer) {
        boolean isPublishRateExceeded = producer.getTopic().isPublishRateExceeded();
        if (++pendingSendRequest == MaxPendingSendRequests || isPublishRateExceeded) {
            // When the quota of pending send requests is reached, stop reading from channel to cause backpressure on
            // client connection
            isAutoRead = false;
            autoReadDisabledRateLimiting = isPublishRateExceeded;
        }
    }

    @Override
    public void completedSendOperation(boolean isNonPersistentTopic, int msgSize) {
        if (--pendingSendRequest == ResumeReadsThreshold) {
            // Resume producer
            isAutoRead = true;
            if (responseObserver.isReady()) {
                responseObserver.request(1);
            }
        }
        if (isNonPersistentTopic) {
            nonPersistentPendingMessages--;
        }
    }

    @Override
    public void enableCnxAutoRead() {
        // we can add check (&& pendingSendRequest < MaxPendingSendRequests) here but then it requires
        // pendingSendRequest to be volatile and it can be expensive while writing. also this will be called on if
        // throttling is enable on the topic. so, avoid pendingSendRequest check will be fine.
        if (!isAutoRead && autoReadDisabledRateLimiting) {
            // Resume reading from socket if pending-request is not reached to threshold
            isAutoRead = true;
            // triggers channel read
            if (responseObserver.isReady()) {
                responseObserver.request(1);
            }
            autoReadDisabledRateLimiting = false;
        }
    }

    public void onMessageHandled() {
        if (responseObserver.isReady() && isAutoRead) {
            responseObserver.request(1);
        } else {
            onReadyHandler.wasReady = false;
        }
    }

    class AutoReadAwareOnReadyHandler implements Runnable {
        // Guard against spurious onReady() calls caused by a race between onNext() and onReady(). If the transport
        // toggles isReady() from false to true while onNext() is executing, but before onNext() checks isReady(),
        // request(1) would be called twice - once by onNext() and once by the onReady() scheduled during onNext()'s
        // execution.
        private boolean wasReady = false;

        @Override
        public void run() {
            if (responseObserver.isReady() && !wasReady) {
                wasReady = true;
                if(isAutoRead) {
                    responseObserver.request(1);
                }
            }
        }
    }

    @Override
    public void sendProducerError(long producerId, long sequenceId, org.apache.pulsar.common.api.proto.PulsarApi.ServerError serverError, String message) {
        responseObserver.onNext(Commands.newSendError(sequenceId, Commands.convertServerError(serverError), message));
    }

    @Override
    public void execute(Runnable runnable) {
        eventLoop.execute(runnable);
    }

    @Override
    public void sendProducerReceipt(long producerId, long sequenceId, long highestSequenceId, long ledgerId, long entryId) {
        responseObserver.onNext(Commands.newSendReceipt(sequenceId, highestSequenceId, ledgerId, entryId));
    }
}
