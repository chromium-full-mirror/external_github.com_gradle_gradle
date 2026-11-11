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

import org.gradle.internal.concurrent.ExecutorFactory;
import org.gradle.internal.concurrent.ManagedExecutor;
import org.gradle.internal.concurrent.Stoppable;
import org.gradle.internal.service.scopes.Scope;
import org.gradle.internal.service.scopes.ServiceScope;
import org.gradle.internal.work.WorkerLimits;
import org.jspecify.annotations.Nullable;

import javax.inject.Inject;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

/**
 * Runs the parsing side of custom HTML test report entries, while the renderer writes on the report generation thread.
 *
 * <p>
 * This must be separate from the build operation executors used to generate report pages, as each page waits for its parser.
 * Sharing a pool could starve parsers of threads and deadlock. A page waits for at most one parser at a time, so a pool the
 * size of the unconstrained build operation pool cannot starve.
 * </p>
 */
@ServiceScope(Scope.BuildTree.class)
public class TestReportEntryRenderingExecutor implements Stoppable {
    private final ExecutorFactory executorFactory;
    private final WorkerLimits workerLimits;
    @Nullable
    private ManagedExecutor executor;
    private boolean stopped;

    @Inject
    public TestReportEntryRenderingExecutor(ExecutorFactory executorFactory, WorkerLimits workerLimits) {
        this.executorFactory = executorFactory;
        this.workerLimits = workerLimits;
    }

    public <T> Future<T> submit(Callable<T> task) {
        return getExecutor().submit(task);
    }

    private synchronized ManagedExecutor getExecutor() {
        if (stopped) {
            throw new IllegalStateException("Test report entry rendering executor has been stopped");
        }
        // Created lazily, as most builds don't use custom report entries
        if (executor == null) {
            executor = executorFactory.create("Test report entry rendering", workerLimits.getMaxUnconstrainedWorkerCount());
        }
        return executor;
    }

    @Override
    public synchronized void stop() {
        stopped = true;
        if (executor != null) {
            executor.stop();
            executor = null;
        }
    }
}
