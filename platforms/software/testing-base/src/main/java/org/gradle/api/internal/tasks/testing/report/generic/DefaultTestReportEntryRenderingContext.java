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

import com.google.common.collect.ImmutableList;
import org.gradle.api.internal.tasks.testing.AssumptionFailureDetails;
import org.gradle.api.internal.tasks.testing.DefaultTestFailure;
import org.gradle.api.internal.tasks.testing.DefaultTestFailureDetails;
import org.gradle.api.internal.tasks.testing.results.DefaultTestResult;
import org.gradle.api.internal.tasks.testing.results.serializable.SerializableFailure;
import org.gradle.api.internal.tasks.testing.results.serializable.SerializableTestResult;
import org.gradle.api.internal.tasks.testing.results.serializable.TestOutputReader;
import org.gradle.api.tasks.testing.TestDescriptor;
import org.gradle.api.tasks.testing.TestFailure;
import org.gradle.api.tasks.testing.TestMetadataEvent;
import org.gradle.api.tasks.testing.TestOutputEvent;
import org.gradle.api.tasks.testing.TestReportEntryRenderingContext;
import org.gradle.api.tasks.testing.TestResult;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Exposes a {@link PerRootInfo} to custom test report entry renderers.
 */
final class DefaultTestReportEntryRenderingContext implements TestReportEntryRenderingContext {
    private final TestDescriptor descriptor;
    private final TestResult result;
    private final Collection<TestOutputEvent> outputEvents;
    private final Collection<TestMetadataEvent> metadataEvents;

    static DefaultTestReportEntryRenderingContext create(PerRootInfo info, TestResult.ResultType resultType, TestOutputReader outputReader) {
        return new DefaultTestReportEntryRenderingContext(
            new ReportTestDescriptor(info),
            createResult(info, resultType),
            outputReader.getAllTestOutputEvents(info.getOutputEntries()),
            ImmutableList.copyOf(info.getMetadatas())
        );
    }

    private DefaultTestReportEntryRenderingContext(
        TestDescriptor descriptor,
        TestResult result,
        Collection<TestOutputEvent> outputEvents,
        Collection<TestMetadataEvent> metadataEvents
    ) {
        this.descriptor = descriptor;
        this.result = result;
        this.outputEvents = outputEvents;
        this.metadataEvents = metadataEvents;
    }

    @Override
    public TestDescriptor getTestDescriptor() {
        return descriptor;
    }

    @Override
    public TestResult getTestResult() {
        return result;
    }

    @Override
    public Collection<TestOutputEvent> getOutputEvents() {
        return outputEvents;
    }

    @Override
    public Collection<TestMetadataEvent> getMetadataEvents() {
        return metadataEvents;
    }

    private static TestResult createResult(PerRootInfo info, TestResult.ResultType resultType) {
        long startTime = Long.MAX_VALUE;
        long endTime = Long.MIN_VALUE;
        ImmutableList.Builder<TestFailure> failures = ImmutableList.builder();
        TestFailure assumptionFailure = null;
        for (SerializableTestResult result : info.getResults()) {
            startTime = Math.min(startTime, result.getStartTime());
            endTime = Math.max(endTime, result.getEndTime());
            for (SerializableFailure failure : result.getFailures()) {
                failures.add(new DefaultTestFailure(
                    null,
                    new DefaultTestFailureDetails(originalMessageOf(failure), failure.getExceptionType(), failure.getStackTrace()),
                    Collections.emptyList()
                ));
            }
            SerializableFailure resultAssumptionFailure = result.getAssumptionFailure();
            if (assumptionFailure == null && resultAssumptionFailure != null) {
                assumptionFailure = new DefaultTestFailure(
                    null,
                    new AssumptionFailureDetails(originalMessageOf(resultAssumptionFailure), resultAssumptionFailure.getExceptionType(), resultAssumptionFailure.getStackTrace()),
                    Collections.emptyList()
                );
            }
        }
        int total = info.getTotalLeafCount();
        int failed = info.getFailedLeafCount();
        int skipped = info.getSkippedLeafCount();
        return new DefaultTestResult(
            resultType,
            startTime,
            endTime,
            total,
            total - failed - skipped,
            failed,
            failures.build(),
            assumptionFailure
        );
    }

    /**
     * Recovers the original message of a failure, as the store saves it like {@link Throwable#toString()}, prefixed with the exception type.
     */
    @Nullable
    private static String originalMessageOf(SerializableFailure failure) {
        String storedMessage = failure.getMessage();
        String exceptionType = failure.getExceptionType();
        if (storedMessage.equals(exceptionType)) {
            return null;
        }
        String prefix = exceptionType + ": ";
        if (storedMessage.startsWith(prefix)) {
            return storedMessage.substring(prefix.length());
        }
        return storedMessage;
    }

    /**
     * A descriptor for stored test results. The parent is not available.
     */
    private static final class ReportTestDescriptor implements TestDescriptor {
        private final String name;
        private final String displayName;
        @Nullable
        private final String className;
        private final boolean composite;

        private ReportTestDescriptor(PerRootInfo info) {
            List<SerializableTestResult> results = info.getResults();
            SerializableTestResult first = results.get(0);
            this.name = first.getName();
            this.displayName = SerializableTestResult.getCombinedDisplayName(results);
            this.className = first.getClassName();
            this.composite = !info.isLeaf();
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDisplayName() {
            return displayName;
        }

        @Override
        @Nullable
        public String getClassName() {
            return className;
        }

        @Override
        public boolean isComposite() {
            return composite;
        }

        @Override
        @Nullable
        public TestDescriptor getParent() {
            return null;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }
}
