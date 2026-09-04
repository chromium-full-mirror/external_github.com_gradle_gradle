/*
 * Copyright 2012 the original author or authors.
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

package org.gradle.process.internal.streams;

import org.gradle.internal.UncheckedException;
import org.gradle.internal.operations.CurrentBuildOperationRef;
import org.jspecify.annotations.Nullable;

import java.io.OutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Objects.requireNonNull;

/**
 * Reads from the process' stdout and stderr (if not merged into stdout) and forwards to {@link OutputStream}.
 */
public class OutputStreamsForwarder implements FinishNotifyingStreamsHandler {
    private final OutputStream standardOutput;
    private final OutputStream errorOutput;
    private final boolean readErrorStream;
    private final AtomicInteger pendingStreams;
    private final CompletableFuture<Void> streamsFinished = new CompletableFuture<>();
    // Set by connectStreams(); the error reader only when readErrorStream is true
    private @Nullable Executor executor;
    private volatile @Nullable ExecOutputHandleRunner standardOutputReader;
    private volatile @Nullable ExecOutputHandleRunner standardErrorReader;

    public OutputStreamsForwarder(OutputStream standardOutput, OutputStream errorOutput, boolean readErrorStream) {
        this.standardOutput = standardOutput;
        this.errorOutput = errorOutput;
        this.readErrorStream = readErrorStream;
        this.pendingStreams = new AtomicInteger(readErrorStream ? 2 : 1);
    }

    @Override
    public void connectStreams(Process process, String processName, Executor executor) {
        this.executor = executor;
        standardOutputReader = new ExecOutputHandleRunner("read standard output of " + processName, process.getInputStream(), standardOutput, this::streamFinished);
        if (readErrorStream) {
            standardErrorReader = new ExecOutputHandleRunner("read error output of " + processName, process.getErrorStream(), errorOutput, this::streamFinished);
        }
    }

    @Override
    public void start() {
        if (readErrorStream) {
            requireNonNull(standardErrorReader).associateBuildOperation(CurrentBuildOperationRef.instance().get());
            requireNonNull(executor).execute(standardErrorReader);
        }
        requireNonNull(standardOutputReader).associateBuildOperation(CurrentBuildOperationRef.instance().get());
        requireNonNull(executor).execute(standardOutputReader);
    }

    @Override
    public void removeStartupContext() {
        requireNonNull(standardOutputReader).clearAssociatedBuildOperation();
        if (readErrorStream) {
            requireNonNull(standardErrorReader).clearAssociatedBuildOperation();
        }
    }

    private void streamFinished() {
        if (pendingStreams.decrementAndGet() == 0) {
            streamsFinished.complete(null);
        }
    }

    @Override
    public void whenStreamsFinished(Runnable callback) {
        CompletableFuture<Void> ignored = streamsFinished.thenRun(callback);
    }

    @Override
    public void stop() {
        try {
            streamsFinished.get();
        } catch (InterruptedException | ExecutionException e) {
            throw UncheckedException.throwAsUncheckedException(e);
        }
    }

    @Override
    public void disconnect() {
        requireNonNull(standardOutputReader).disconnect();
        if (readErrorStream) {
            requireNonNull(standardErrorReader).disconnect();
        }
    }
}
