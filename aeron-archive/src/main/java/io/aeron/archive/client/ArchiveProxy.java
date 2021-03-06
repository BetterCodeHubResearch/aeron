/*
 * Copyright 2014-2017 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.archive.client;

import io.aeron.Publication;
import io.aeron.archive.codecs.*;
import org.agrona.ExpandableDirectByteBuffer;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.NanoClock;
import org.agrona.concurrent.SystemNanoClock;
import org.agrona.concurrent.YieldingIdleStrategy;

import static io.aeron.archive.client.AeronArchive.Configuration.MESSAGE_TIMEOUT_DEFAULT_NS;

/**
 * Proxy class for encapsulating encoding and sending of control protocol messages to an archive.
 */
public class ArchiveProxy
{
    /**
     * Default maximum number of retry attempts to be made at offering requests.
     */
    public static final int DEFAULT_MAX_RETRY_ATTEMPTS = 3;

    private final long connectTimeoutNs;
    private final int maxRetryAttempts;
    private final IdleStrategy retryIdleStrategy;
    private final NanoClock nanoClock;

    private final ExpandableDirectByteBuffer buffer = new ExpandableDirectByteBuffer(1024);
    private final Publication publication;
    private final MessageHeaderEncoder messageHeaderEncoder = new MessageHeaderEncoder();
    private final ConnectRequestEncoder connectRequestEncoder = new ConnectRequestEncoder();
    private final CloseSessionRequestEncoder closeSessionRequestEncoder = new CloseSessionRequestEncoder();
    private final StartRecordingRequestEncoder startRecordingRequestEncoder = new StartRecordingRequestEncoder();
    private final ReplayRequestEncoder replayRequestEncoder = new ReplayRequestEncoder();
    private final StopReplayRequestEncoder stopReplayRequestEncoder = new StopReplayRequestEncoder();
    private final StopRecordingRequestEncoder stopRecordingRequestEncoder = new StopRecordingRequestEncoder();
    private final ListRecordingsRequestEncoder listRecordingsRequestEncoder = new ListRecordingsRequestEncoder();
    private final ListRecordingsForUriRequestEncoder listRecordingsForUriRequestEncoder =
        new ListRecordingsForUriRequestEncoder();

    /**
     * Create a proxy with a {@link Publication} for sending control message requests.
     * <p>
     * This provides a default {@link IdleStrategy} of a {@link YieldingIdleStrategy} when offers are back pressured
     * with a defaults of {@link AeronArchive.Configuration#MESSAGE_TIMEOUT_DEFAULT_NS} and
     * {@link #DEFAULT_MAX_RETRY_ATTEMPTS}.
     *
     * @param publication publication for sending control messages to an archive.
     */
    public ArchiveProxy(final Publication publication)
    {
        this(
            publication,
            new YieldingIdleStrategy(),
            new SystemNanoClock(),
            MESSAGE_TIMEOUT_DEFAULT_NS,
            DEFAULT_MAX_RETRY_ATTEMPTS);
    }

    /**
     * Create a proxy with a {@link Publication} for sending control message requests.
     *
     * @param publication       publication for sending control messages to an archive.
     * @param retryIdleStrategy for what should happen between retry attempts at offering messages.
     * @param nanoClock         to be used for calculating checking deadlines.
     * @param connectTimeoutNs  for for connection requests.
     * @param maxRetryAttempts  for offering control messages before giving up.
     */
    public ArchiveProxy(
        final Publication publication,
        final IdleStrategy retryIdleStrategy,
        final NanoClock nanoClock,
        final long connectTimeoutNs,
        final int maxRetryAttempts)
    {
        this.publication = publication;
        this.retryIdleStrategy = retryIdleStrategy;
        this.nanoClock = nanoClock;
        this.connectTimeoutNs = connectTimeoutNs;
        this.maxRetryAttempts = maxRetryAttempts;
    }

    /**
     * Get the {@link Publication} used for sending control messages.
     *
     * @return the {@link Publication} used for sending control messages.
     */
    public Publication publication()
    {
        return publication;
    }

    /**
     * Connect to an archive on its control interface providing the response stream details.
     *
     * @param responseChannel  for the control message responses.
     * @param responseStreamId for the control message responses.
     * @param correlationId    for this request.
     * @return true if successfully offered otherwise false.
     */
    public boolean connect(final String responseChannel, final int responseStreamId, final long correlationId)
    {
        connectRequestEncoder
            .wrapAndApplyHeader(buffer, 0, messageHeaderEncoder)
            .correlationId(correlationId)
            .responseStreamId(responseStreamId)
            .responseChannel(responseChannel);

        return offerWithTimeout(connectRequestEncoder.encodedLength());
    }

    /**
     * Close this control session with the archive.
     *
     * @param controlSessionId with the archive.
     * @return true if successfully offered otherwise false.
     */
    public boolean closeSession(final long controlSessionId)
    {
        closeSessionRequestEncoder
            .wrapAndApplyHeader(buffer, 0, messageHeaderEncoder)
            .controlSessionId(controlSessionId);

        return offer(closeSessionRequestEncoder.encodedLength());
    }

    /**
     * Start recording streams for a given channel and stream id pairing.
     *
     * @param channel          to be recorded.
     * @param streamId         to be recorded.
     * @param sourceLocation   of the publication to be recorded.
     * @param correlationId    for this request.
     * @param controlSessionId for this request.
     * @return true if successfully offered otherwise false.
     */
    public boolean startRecording(
        final String channel,
        final int streamId,
        final SourceLocation sourceLocation,
        final long correlationId,
        final long controlSessionId)
    {
        startRecordingRequestEncoder
            .wrapAndApplyHeader(buffer, 0, messageHeaderEncoder)
            .controlSessionId(controlSessionId)
            .correlationId(correlationId)
            .streamId(streamId)
            .sourceLocation(sourceLocation)
            .channel(channel);

        return offer(startRecordingRequestEncoder.encodedLength());
    }

    /**
     * Stop an active recording.
     *
     * @param channel          to be stopped.
     * @param streamId         to be stopped.
     * @param correlationId    for this request.
     * @param controlSessionId for this request.
     * @return true if successfully offered otherwise false.
     */
    public boolean stopRecording(
        final String channel,
        final int streamId,
        final long correlationId,
        final long controlSessionId)
    {
        stopRecordingRequestEncoder
            .wrapAndApplyHeader(buffer, 0, messageHeaderEncoder)
            .controlSessionId(controlSessionId)
            .correlationId(correlationId)
            .streamId(streamId)
            .channel(channel);

        return offer(stopRecordingRequestEncoder.encodedLength());
    }

    /**
     * Replay a recording from a given position.
     *
     * @param recordingId      to be replayed.
     * @param position         from which the replay should be started.
     * @param length           of the stream to be replayed.
     * @param replayChannel    to which the replay should be sent.
     * @param replayStreamId   to which the replay should be sent.
     * @param correlationId    for this request.
     * @param controlSessionId for this request.
     * @return true if successfully offered otherwise false.
     */
    public boolean replay(
        final long recordingId,
        final long position,
        final long length,
        final String replayChannel,
        final int replayStreamId,
        final long correlationId,
        final long controlSessionId)
    {
        replayRequestEncoder
            .wrapAndApplyHeader(buffer, 0, messageHeaderEncoder)
            .controlSessionId(controlSessionId)
            .correlationId(correlationId)
            .recordingId(recordingId)
            .position(position)
            .length(length)
            .replayStreamId(replayStreamId)
            .replayChannel(replayChannel);

        return offer(replayRequestEncoder.encodedLength());
    }

    /**
     * Stop an existing replay session.
     *
     * @param replaySessionId  that should be stopped.
     * @param correlationId    for this request.
     * @param controlSessionId for this request.
     * @return true if successfully offered otherwise false.
     */
    public boolean stopReplay(
        final long replaySessionId,
        final long correlationId,
        final long controlSessionId)
    {
        stopReplayRequestEncoder
            .wrapAndApplyHeader(buffer, 0, messageHeaderEncoder)
            .controlSessionId(controlSessionId)
            .correlationId(correlationId)
            .replaySessionId(replaySessionId);

        return offer(replayRequestEncoder.encodedLength());
    }

    /**
     * List a range of recording descriptors.
     *
     * @param fromRecordingId  at which to begin listing.
     * @param recordCount      for the number of descriptors to be listed.
     * @param correlationId    for this request.
     * @param controlSessionId for this request.
     * @return true if successfully offered otherwise false.
     */
    public boolean listRecordings(
        final long fromRecordingId,
        final int recordCount,
        final long correlationId,
        final long controlSessionId)
    {
        listRecordingsRequestEncoder
            .wrapAndApplyHeader(buffer, 0, messageHeaderEncoder)
            .controlSessionId(controlSessionId)
            .correlationId(correlationId)
            .fromRecordingId(fromRecordingId)
            .recordCount(recordCount);

        return offer(listRecordingsRequestEncoder.encodedLength());
    }

    /**
     * List a range of recording descriptors which match a channel and stream id.
     *
     * @param fromRecordingId  at which to begin listing.
     * @param recordCount      for the number of descriptors to be listed.
     * @param channel          to match recordings on.
     * @param streamId         to match recordings on.
     * @param correlationId    for this request.
     * @param controlSessionId for this request.
     * @return true if successfully offered otherwise false.
     */
    public boolean listRecordingsForUri(
        final long fromRecordingId,
        final int recordCount,
        final String channel,
        final int streamId,
        final long correlationId,
        final long controlSessionId)
    {
        listRecordingsForUriRequestEncoder
            .wrapAndApplyHeader(buffer, 0, messageHeaderEncoder)
            .controlSessionId(controlSessionId)
            .correlationId(correlationId)
            .fromRecordingId(fromRecordingId)
            .recordCount(recordCount)
            .streamId(streamId)
            .channel(channel);

        return offer(listRecordingsForUriRequestEncoder.encodedLength());
    }

    private boolean offer(final int length)
    {
        retryIdleStrategy.reset();

        int attempts = 0;
        while (true)
        {
            final long result;
            if ((result = publication.offer(buffer, 0, MessageHeaderEncoder.ENCODED_LENGTH + length)) > 0)
            {
                return true;
            }

            if (result == Publication.NOT_CONNECTED)
            {
                throw new IllegalStateException("Connection to the archive is no longer available");
            }

            if (result == Publication.MAX_POSITION_EXCEEDED)
            {
                throw new IllegalStateException("Publication failed due to max position being reached");
            }

            if (++attempts > maxRetryAttempts)
            {
                return false;
            }

            retryIdleStrategy.idle();
        }
    }

    private boolean offerWithTimeout(final int length)
    {
        retryIdleStrategy.reset();

        final long deadlineNs = nanoClock.nanoTime() + connectTimeoutNs;
        while (true)
        {
            final long result;
            if ((result = publication.offer(buffer, 0, MessageHeaderEncoder.ENCODED_LENGTH + length)) > 0)
            {
                return true;
            }

            if (result == Publication.MAX_POSITION_EXCEEDED)
            {
                throw new IllegalStateException("Publication failed due to max position being reached");
            }

            if (nanoClock.nanoTime() > deadlineNs)
            {
                return false;
            }

            retryIdleStrategy.idle();
        }
    }
}
