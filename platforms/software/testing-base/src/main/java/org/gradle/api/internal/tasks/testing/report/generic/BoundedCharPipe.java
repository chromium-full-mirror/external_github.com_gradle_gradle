/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.tasks.testing.report.generic;

import org.jctools.queues.SpscUnboundedArrayQueue;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.Reader;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import static com.google.common.base.Preconditions.checkPositionIndexes;

/**
 * A bounded, in-memory pipe of characters from one writing thread to one reading thread.
 *
 * <p>
 * The pipe is a lock-free single-producer single-consumer queue of the strings that were appended, so text is not copied until it is read.
 * Strings are kept by reference, other {@link CharSequence}s are converted to strings, as they may be mutable.
 * </p>
 *
 * <ul>
 *     <li>The writer parks while the queued text is at capacity. A single string larger than the capacity is still accepted
 *     once the queue is empty. Once the read side is closed, appends fail with an {@link IOException}.</li>
 *     <li>The reader parks when the pipe is empty. Once the write side is closed and the pipe is drained, reads return EOF.</li>
 * </ul>
 *
 * <p>
 * A side only parks after publishing itself as waiting and then re-checking its condition, and the other side unparks a published
 * waiter after changing the state it waits on. As all of these are volatile accesses, a wakeup cannot be lost.
 * </p>
 *
 * <p>
 * {@link #getAppendable()} and {@link #closeWrite()} must only be used by the writing thread, and {@link #getReader()} and
 * {@link #closeRead(Throwable)} only by the reading thread.
 * </p>
 */
final class BoundedCharPipe {
    private static final int QUEUE_CHUNK_SIZE = 64;

    private record Chunk(String text, int start, int end) {
    }

    private static void unpark(@Nullable Thread thread) {
        if (thread != null) {
            LockSupport.unpark(thread);
        }
    }

    private final int capacity;
    private final SpscUnboundedArrayQueue<Chunk> chunks = new SpscUnboundedArrayQueue<>(QUEUE_CHUNK_SIZE);
    /**
     * Characters appended but not yet read, used for backpressure.
     */
    private final AtomicLong queuedChars = new AtomicLong();
    private volatile boolean writeClosed;
    private volatile boolean readClosed;
    @Nullable
    private volatile Throwable readFailure;
    @Nullable
    private volatile Thread waitingWriter;
    @Nullable
    private volatile Thread waitingReader;
    /**
     * The current chunk being read from the queue. Only accessed by the reading thread.
     */
    @Nullable
    private Chunk currentChunk;
    /**
     * The position in {@link #currentChunk}. Not an offset from {@code start}, but actually starts there.
     * Only accessed by the reading thread.
     */
    private int currentPosition;

    private final Appendable appendable = new PipeAppendable();
    private final Reader reader = new PipeReader();

    BoundedCharPipe(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
    }

    Appendable getAppendable() {
        return appendable;
    }

    Reader getReader() {
        return reader;
    }

    /**
     * Closes the write side. The reader sees EOF once it has read all queued text.
     */
    void closeWrite() {
        writeClosed = true;
        unpark(waitingReader);
    }

    /**
     * Closes the read side, discarding queued text. Further appends fail, with the given failure as the cause if present.
     * Only the first call has an effect, so the first failure is kept.
     */
    void closeRead(@Nullable Throwable failure) {
        if (readClosed) {
            return;
        }
        // Set failure first to ensure that it's seen when `readClosed` is true
        readFailure = failure;
        readClosed = true;
        currentChunk = null;
        chunks.clear();
        unpark(waitingWriter);
    }

    private void checkWritable() throws IOException {
        if (readClosed) {
            throw new IOException("Pipe closed by reader", readFailure);
        }
        if (writeClosed) {
            throw new IOException("Pipe closed by writer");
        }
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Interrupted while waiting on pipe");
        }
    }

    private void append(String text, int start, int end) throws IOException {
        int length = end - start;
        if (length == 0) {
            return;
        }
        checkWritable();
        while (noSpaceFor(length)) {
            waitingWriter = Thread.currentThread();
            try {
                if (noSpaceFor(length) && !readClosed) {
                    LockSupport.park(this);
                }
            } finally {
                waitingWriter = null;
            }
            checkInterrupted();
            checkWritable();
        }
        // Note: offer + add without sync is only safe under single producer assumption.
        chunks.offer(new Chunk(text, start, end));
        queuedChars.addAndGet(length);
        unpark(waitingReader);
    }

    private boolean noSpaceFor(int length) {
        long queued = queuedChars.get();
        // Always accept text into an empty pipe, so a string larger than the capacity can't block forever
        return queued != 0 && queued + length > capacity;
    }

    private int read(char[] chars, int offset, int length) throws IOException {
        if (length == 0) {
            return 0;
        }
        while (true) {
            if (readClosed) {
                throw new IOException("Pipe closed by reader");
            }
            // Read the flag before peeking: the writer offers everything before closing,
            // so reading in this order ensures that an empty queue is really EOF.
            boolean closed = writeClosed;
            if (currentChunk() != null) {
                break;
            }
            if (closed) {
                return -1;
            }
            waitingReader = Thread.currentThread();
            try {
                // Re-check with the count rather than the queue as it uses a full memory fence
                if (queuedChars.get() == 0 && !writeClosed) {
                    LockSupport.park(this);
                }
            } finally {
                waitingReader = null;
            }
            checkInterrupted();
        }

        int read = 0;
        Chunk chunk;
        while (read < length && (chunk = currentChunk()) != null) {
            int toRead = Math.min(chunk.end - currentPosition, length - read);
            chunk.text.getChars(currentPosition, currentPosition + toRead, chars, offset + read);
            currentPosition += toRead;
            read += toRead;
            if (currentPosition == chunk.end) {
                currentChunk = null;
            }
        }
        queuedChars.addAndGet(-read);
        unpark(waitingWriter);
        return read;
    }

    /**
     * Returns the chunk being read, polling the next one from the queue if the previous one was fully read.
     */
    @Nullable
    private Chunk currentChunk() {
        if (currentChunk == null) {
            Chunk next = chunks.poll();
            if (next != null) {
                currentChunk = next;
                currentPosition = next.start;
            }
        }
        return currentChunk;
    }

    private final class PipeAppendable implements Appendable {
        @Override
        public Appendable append(@Nullable CharSequence csq) throws IOException {
            String text = String.valueOf(csq);
            BoundedCharPipe.this.append(text, 0, text.length());
            return this;
        }

        @Override
        public Appendable append(@Nullable CharSequence csq, int start, int end) throws IOException {
            if (csq instanceof String text) {
                // Keep a reference to the range instead of copying it
                checkPositionIndexes(start, end, text.length());
                BoundedCharPipe.this.append(text, start, end);
            } else {
                // Have to make a copy because it might be mutated after this method returns.
                CharSequence sequence = csq == null ? "null" : csq;
                String text = sequence.subSequence(start, end).toString();
                BoundedCharPipe.this.append(text, 0, text.length());
            }
            return this;
        }

        @Override
        public Appendable append(char c) throws IOException {
            // TODO Possibly consider buffering this, since we have to allocate anyways.
            //   Even in the event of being followed by a full sequence append, the combined
            //   allocation happening once is probably better.
            String text = String.valueOf(c);
            BoundedCharPipe.this.append(text, 0, 1);
            return this;
        }
    }

    private final class PipeReader extends Reader {
        @Override
        public int read(char[] cbuf, int off, int len) throws IOException {
            return BoundedCharPipe.this.read(cbuf, off, len);
        }

        @Override
        public void close() {
            closeRead(null);
        }
    }
}
