/*
 * Copyright 2024 the original author or authors.
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

package org.gradle.api.internal.tasks.testing;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.gradle.api.file.Directory;
import org.gradle.api.internal.tasks.testing.logging.SimpleTestEventLogger;
import org.gradle.api.internal.tasks.testing.logging.TestEventProgressListener;
import org.gradle.api.internal.tasks.testing.report.generic.GenericHtmlTestReportGenerator;
import org.gradle.api.internal.tasks.testing.results.TestExecutionResultsListener;
import org.gradle.api.internal.tasks.testing.results.TestListenerInternal;
import org.gradle.api.internal.tasks.testing.results.serializable.SerializableTestResultStore;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.tasks.testing.GroupTestEventReporter;
import org.gradle.api.tasks.testing.HtmlEmittingTestReportEntryRenderer;
import org.gradle.internal.UncheckedException;
import org.gradle.internal.event.ListenerBroadcast;
import org.gradle.internal.event.ListenerManager;
import org.gradle.internal.id.LongIdGenerator;
import org.gradle.internal.logging.progress.ProgressLoggerFactory;
import org.gradle.internal.logging.text.StyledTextOutputFactory;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import javax.inject.Inject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongFunction;

@NullMarked
public final class DefaultTestEventReporterFactory implements TestEventReporterFactoryInternal {

    private final ListenerManager listenerManager;
    private final StyledTextOutputFactory textOutputFactory;
    private final ProgressLoggerFactory progressLoggerFactory;
    private final ObjectFactory objectFactory;

    @Inject
    public DefaultTestEventReporterFactory(
        ListenerManager listenerManager,
        StyledTextOutputFactory textOutputFactory,
        ProgressLoggerFactory progressLoggerFactory,
        ObjectFactory objectFactory
    ) {
        this.listenerManager = listenerManager;
        this.textOutputFactory = textOutputFactory;
        this.progressLoggerFactory = progressLoggerFactory;
        this.objectFactory = objectFactory;
    }

    /**
     * Names of the tabs the generic HTML report renders itself, which custom entries must not use.
     */
    private static final ImmutableSet<String> RESERVED_TAB_NAMES = ImmutableSet.of(
        "summary",
        "standard output",
        "error output",
        "data",
        "attachments"
    );

    @Override
    public GroupTestEventReporter createTestEventReporter(
        String rootName,
        Directory binaryResultsDirectory,
        Directory htmlReportDirectory,
        boolean closeThrowsOnTestFailures,
        List<? extends HtmlEmittingTestReportEntryRenderer> htmlReportEntryRenderers
    ) {
        List<HtmlEmittingTestReportEntryRenderer> entryRenderers = ImmutableList.copyOf(htmlReportEntryRenderers);
        validateTabNames(entryRenderers);

        ListenerBroadcast<TestListenerInternal> testListenerInternalBroadcaster = listenerManager.createAnonymousBroadcaster(TestListenerInternal.class);

        // Renders console output for the task
        testListenerInternalBroadcaster.add(new SimpleTestEventLogger(textOutputFactory));

        // Emits progress logger events
        testListenerInternalBroadcaster.add(new TestEventProgressListener(progressLoggerFactory));

        GenericHtmlTestReportGenerator reportGenerator = objectFactory.newInstance(GenericHtmlTestReportGenerator.class, htmlReportDirectory.getAsFile().toPath(), entryRenderers);
        return createInternalTestEventReporter(
            id -> new DefaultTestSuiteDescriptor(id, rootName),
            binaryResultsDirectory,
            reportGenerator,
            testListenerInternalBroadcaster,
            0,
            closeThrowsOnTestFailures,
            true
        );
    }

    private static void validateTabNames(List<HtmlEmittingTestReportEntryRenderer> entryRenderers) {
        Set<String> seenTabNames = new HashSet<>();
        for (HtmlEmittingTestReportEntryRenderer renderer : entryRenderers) {
            String tabName = renderer.getTabName();
            String rendererDescription = "Test report entry renderer " + renderer.getClass().getName();
            if (tabName.trim().isEmpty()) {
                throw new IllegalArgumentException(rendererDescription + " must have a non-blank tab name.");
            }
            if (!tabName.trim().equals(tabName)) {
                throw new IllegalArgumentException(rendererDescription + " has tab name '" + tabName + "' with leading or trailing whitespace.");
            }
            if (RESERVED_TAB_NAMES.contains(tabName)) {
                throw new IllegalArgumentException(rendererDescription + " has tab name '" + tabName + "', which is reserved for the built-in tabs of the test report.");
            }
            if (!seenTabNames.add(tabName)) {
                throw new IllegalArgumentException(rendererDescription + " has tab name '" + tabName + "', which is already used by another renderer.");
            }
        }
    }

    @Override
    public GroupTestEventReporterInternal createInternalTestEventReporter(
        LongFunction<TestDescriptorInternal> rootDescriptorFactory,
        Directory binaryResultsDirectory,
        @Nullable TestReportGenerator reportGenerator,
        ListenerBroadcast<TestListenerInternal> testListenerInternalBroadcaster,
        int diskSkipLevels,
        boolean closeThrowsOnTestFailures,
        boolean addToAggregateReports
    ) {
        // Record all emitted results to disk
        Path binaryResultsDir = binaryResultsDirectory.getAsFile().toPath();
        SerializableTestResultStore.Writer resultsSerializingListener;
        try {
            resultsSerializingListener = new SerializableTestResultStore(binaryResultsDir).openWriter(diskSkipLevels);
        } catch (IOException e) {
            throw UncheckedException.throwAsUncheckedException(e);
        }
        testListenerInternalBroadcaster.add(resultsSerializingListener);

        TestListenerInternal testListenerBroadcaster = testListenerInternalBroadcaster.getSource();
        TestExecutionResultsListener executionResultsListenerBroadcaster = listenerManager.getBroadcaster(TestExecutionResultsListener.class);

        return new LifecycleTrackingGroupTestEventReporter(new DefaultRootTestEventReporter(
            rootDescriptorFactory,
            testListenerBroadcaster,
            new LongIdGenerator(),
            binaryResultsDir,
            resultsSerializingListener,
            reportGenerator,
            executionResultsListenerBroadcaster,
            closeThrowsOnTestFailures,
            addToAggregateReports
        ));
    }
}
