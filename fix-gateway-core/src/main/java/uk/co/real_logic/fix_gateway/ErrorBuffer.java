/*
 * Copyright 2015 Real Logic Ltd.
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
package uk.co.real_logic.fix_gateway;

import uk.co.real_logic.agrona.ErrorHandler;
import uk.co.real_logic.agrona.concurrent.AtomicBuffer;
import uk.co.real_logic.agrona.concurrent.AtomicCounter;
import uk.co.real_logic.fix_gateway.messages.*;
import uk.co.real_logic.fix_gateway.util.MilliClock;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static uk.co.real_logic.agrona.BitUtil.SIZE_OF_INT;

/**
 * A buffer which keeps track of the last exception thrown from a callsite.
 *
 * Instances are single threaded, but buffers are threadsafe to read from another thread.
 *
 * NB: its possible in a multi-threaded scenario to log two exceptions with the same hash,
 * however, exceptions are still bounded in worst case by number of threads * number of hashes.
 */
// TODO: move to a fixed size
public class ErrorBuffer implements ErrorHandler
{
    // TODO: split out unused and pending to avoid double claiming a slot
    private static final int PENDING = 0;
    private static final int COMMITTED = 1;
    private static final int ABORTED = 2;

    private static final int NO_SPACE_LEFT = -1;

    private static final int MAX_STACK_TRACE_SIZE = 10;
    private static final int POSITION_FIELD_OFFSET = MessageHeaderEncoder.ENCODED_LENGTH;
    private static final int END_OF_POSITION_FIELD = POSITION_FIELD_OFFSET + SIZE_OF_INT;
    private static final int EXCEPTION_ENTRY_MIN = ExceptionEntryEncoder.BLOCK_LENGTH + 4;
    private static final int STACK_TRACE_ELEMENT_MIN = StackTraceElementEncoder.BLOCK_LENGTH + 6;

    private final MessageHeaderEncoder messageHeaderEncoder = new MessageHeaderEncoder();
    private final MessageHeaderDecoder messageHeaderDecoder = new MessageHeaderDecoder();
    private final ExceptionEntryEncoder exceptionEntryEncoder = new ExceptionEntryEncoder();
    private final ExceptionEntryDecoder exceptionDecoder = new ExceptionEntryDecoder();
    private final StackTraceElementEncoder stackTraceElementEncoder = new StackTraceElementEncoder();
    private final StackTraceElementDecoder stackTraceElementDecoder = new StackTraceElementDecoder();
    private final int exceptionBlockLength = exceptionEntryEncoder.sbeBlockLength();
    private final int exceptionVersion = exceptionEntryEncoder.sbeSchemaVersion();
    private final int elementBlockLength = stackTraceElementEncoder.sbeBlockLength();
    private final int elementVersion = stackTraceElementEncoder.sbeSchemaVersion();

    private final AtomicBuffer buffer;
    private final AtomicCounter counter;
    private final MilliClock clock;

    private boolean isReusedSlot;

    /**
     * Read only constructor.
     *
     * @param buffer the buffer to use for outputting errors on.
     */
    public ErrorBuffer(final AtomicBuffer buffer)
    {
        this(buffer, null, null);
    }

    public ErrorBuffer(final AtomicBuffer buffer, final AtomicCounter counter, final MilliClock clock)
    {
        this.buffer = buffer;
        this.counter = counter;
        this.clock = clock;

        setupBuffers(buffer);
    }

    private void setupBuffers(final AtomicBuffer buffer)
    {
        messageHeaderDecoder.wrap(buffer, 0);
        messageHeaderEncoder.wrap(buffer, 0);

        if (isFreshBuffer())
        {
            writeHeader();
        }
        else
        {
            validateHeader();
        }
    }

    private void validateHeader()
    {
        validate(messageHeaderDecoder.schemaId(), exceptionEntryEncoder.sbeSchemaId(), "Schema Id");
        validate(messageHeaderDecoder.version(), exceptionEntryEncoder.sbeSchemaVersion(), "Schema Version");
    }

    private void writeHeader()
    {
        messageHeaderEncoder
            .blockLength(exceptionBlockLength)
            .templateId(exceptionEntryEncoder.sbeTemplateId())
            .schemaId(exceptionEntryEncoder.sbeSchemaId())
            .version(exceptionVersion);

        claimSlot(END_OF_POSITION_FIELD);
    }

    private void validate(final int read, final int expected, final String name)
    {
        if (read != expected)
        {
            throw new IllegalStateException(
                String.format("Wrong %s, expected %d, but was %d", name, read, expected));
        }
    }

    private boolean isFreshBuffer()
    {
        return messageHeaderDecoder.schemaId() == 0;
    }

    /**
     * Read out the current list of errors that have been saved.
     *
     * @return a list of errors, each exception represented as a string.
     */
    public List<String> errors()
    {
        return errorsSince(0L);
    }

    public List<String> errorsSince(final long timeInMillis)
    {
        final List<String> errors = new ArrayList<>();

        int offset = END_OF_POSITION_FIELD;

        final int position = position();
        while (offset < position)
        {
            wrapExceptionDecoder(offset);
            if (status(offset) == COMMITTED && exceptionDecoder.time() > timeInMillis)
            {
                final StringBuilder builder = new StringBuilder();
                appendException(builder);
                appendStackTraceElements(exceptionDecoder.limit(), builder);
                errors.add(builder.toString());
            }
            offset += exceptionDecoder.size();
        }

        return errors;
    }

    private int status(final int offset)
    {
        return buffer.getIntVolatile(offset);
    }

    private void status(final int offset, final int oldStatus, final int newStatus)
    {
        while (!buffer.compareAndSetInt(offset, oldStatus, newStatus))
        {
            LockSupport.parkNanos(MICROSECONDS.toNanos(1));
        }
    }

    private void appendStackTraceElements(int offset, final StringBuilder builder)
    {
        final int stackTraceSize = exceptionDecoder.elementCount();
        for (int i = 0; i < stackTraceSize; i++)
        {
            stackTraceElementDecoder.wrap(buffer, offset, elementBlockLength, elementVersion);

            appendStackTraceElement(builder);

            offset = stackTraceElementDecoder.limit();
        }
    }

    private void appendStackTraceElement(final StringBuilder builder)
    {
        builder.append(String.format(
            "\n%s.%s(%s:%d)",
            stackTraceElementDecoder.className(),
            stackTraceElementDecoder.methodName(),
            stackTraceElementDecoder.fileName(),
            stackTraceElementDecoder.lineNumber()));
    }

    private void appendException(final StringBuilder builder)
    {
        builder.append(formatTimeStamp());
        builder.append(": ");
        builder.append(exceptionDecoder.exceptionClassName());
        builder.append("(");
        builder.append(exceptionDecoder.message());
        builder.append(")");
    }

    private void wrapExceptionDecoder(final int offset)
    {
        exceptionDecoder.wrap(buffer, offset, exceptionBlockLength, exceptionVersion);
    }

    private String formatTimeStamp()
    {
        final Instant instant = Instant.ofEpochMilli(exceptionDecoder.time());
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault()).toString();
    }

    /**
     * {@inheritDoc}
     */
    public void onError(final Throwable ex)
    {
        counter.orderedIncrement();

        final StackTraceElement[] stackTrace = ex.getStackTrace();
        final int hash = hashThrowSite(stackTrace[0]);
        final int stackTraceSize = Math.min(stackTrace.length, MAX_STACK_TRACE_SIZE);
        final String message = ex.getMessage() != null ? ex.getMessage() : "No Message";
        final String exceptionName = ex.getClass().getName();
        final int size = sizeInBytes(stackTrace, stackTraceSize, message, exceptionName);

        final int claimedOffset = findSlot(size, hash);
        if (claimedOffset == NO_SPACE_LEFT)
        {
            return;
        }

        int offset = claimedOffset;

        exceptionEntryEncoder
            .wrap(buffer, offset)
            .hash(hash)
            .time(clock.time())
            .elementCount((byte) stackTraceSize)
            .exceptionClassName(exceptionName)
            .message(message);

        offset = exceptionEntryEncoder.limit();

        if (!isReusedSlot)
        {
            exceptionEntryEncoder.size(size);
        }

        for (int i = 0; i < stackTraceSize; i++)
        {
            final StackTraceElement element = stackTrace[i];

            stackTraceElementEncoder
                .wrap(buffer, offset)
                .lineNumber(element.getLineNumber())
                .className(element.getClassName())
                .methodName(element.getMethodName())
                .fileName(element.getFileName());

            offset = stackTraceElementEncoder.limit();
        }

        status(claimedOffset, PENDING, COMMITTED);

        if (claimedOffset + size != offset)
        {
            System.err.printf(
                "Unexpected offset logging errors, claimedOffset = %d, size = %d, offset = %d\n",
                claimedOffset,
                size,
                offset);
        }
    }

    private int findSlot(final int requiredSize, final int hash)
    {
        int offset = END_OF_POSITION_FIELD;

        while (offset < position())
        {
            wrapExceptionDecoder(offset);
            final int slotSize = exceptionDecoder.size();
            if (hash == exceptionDecoder.hash() && status(offset) == COMMITTED)
            {
                if (requiredSize <= slotSize)
                {
                    status(offset, COMMITTED, PENDING);
                    isReusedSlot = true;
                    return offset;
                }
                else
                {
                    status(offset, COMMITTED, ABORTED);
                }
            }

            offset += slotSize;
        }

        if ((offset + requiredSize) > buffer.capacity())
        {
            return NO_SPACE_LEFT;
        }

        isReusedSlot = false;
        final int claimedOffset = claimSlot(requiredSize);
        return claimedOffset;
    }

    private int sizeInBytes(final StackTraceElement[] stackTrace,
                            final int stackTraceSize,
                            final String message,
                            final String exceptionName)
    {
        int size = EXCEPTION_ENTRY_MIN + message.length() + exceptionName.length();
        for (int i = 0; i < stackTraceSize; i++)
        {
            final StackTraceElement element = stackTrace[i];
            size += STACK_TRACE_ELEMENT_MIN;
            size += element.getClassName().length();
            size += element.getMethodName().length();
            size += element.getFileName().length();
        }
        return size;
    }

    private int hashThrowSite(final StackTraceElement throwSite)
    {
        return throwSite.getClassName().hashCode() + throwSite.getLineNumber() * 31;
    }

    private int position()
    {
        return buffer.getIntVolatile(POSITION_FIELD_OFFSET);
    }

    private int claimSlot(final int delta)
    {
        return buffer.getAndAddInt(POSITION_FIELD_OFFSET, delta);
    }
}
