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

package org.gradle.api.internal.tasks.testing.report.generic

import org.gradle.api.internal.tasks.testing.DefaultTestOutputEvent
import org.gradle.api.internal.tasks.testing.TestCompleteEvent
import org.gradle.api.internal.tasks.testing.TestDescriptorInternal
import org.gradle.api.internal.tasks.testing.TestStartEvent
import org.gradle.api.internal.tasks.testing.results.serializable.SerializableTestResultStore
import org.gradle.api.internal.tasks.testing.results.serializable.TestOutputReader
import org.gradle.api.internal.tasks.testing.worker.TestEventSerializer
import org.gradle.api.tasks.testing.TestOutputEvent
import org.gradle.api.tasks.testing.TestResult
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

import static org.gradle.api.tasks.testing.TestOutputEvent.Destination.StdErr
import static org.gradle.api.tasks.testing.TestOutputEvent.Destination.StdOut

class LazyOutputEventsTest extends Specification {
    @TempDir
    Path tempDir

    private int nextDescriptorId = 1

    def "reads all output events of a test in write order across destinations and batches (#eventCount events)"() {
        given:
        def store = writeInterleavedOutput(eventCount)

        when:
        def messages = withOutputEventsOf(store, "testA") { Collection<TestOutputEvent> events ->
            assert events.size() == eventCount
            events.collect { "${it.destination}:${it.message}" as String }
        }

        then:
        messages == (0..<eventCount).collect { (it % 2 == 0 ? "StdOut" : "StdErr") + ":A" + it }

        where:
        // The reader reads in batches of 256
        eventCount << [1, 255, 256, 257, 1000]
    }

    def "can iterate partially and then iterate again"() {
        given:
        def store = writeInterleavedOutput(600)

        when:
        def result = withOutputEventsOf(store, "testA") { Collection<TestOutputEvent> events ->
            def iterator = events.iterator()
            def first = (0..<300).collect { iterator.next().message }
            def all = events.collect { it.message }
            [first, all]
        }

        then:
        result[0] == (0..<300).collect { "A" + it }
        result[1] == (0..<600).collect { "A" + it }
    }

    def "test without output has no output events"() {
        given:
        def store = writeStore { writer ->
            def root = descriptor("root", null)
            def test = descriptor("testA", root)
            writer.started(root, new TestStartEvent(0))
            writer.started(test, new TestStartEvent(0))
            writer.completed(test, result(), new TestCompleteEvent(1))
            writer.completed(root, result(), new TestCompleteEvent(1))
        }

        when:
        def result = withOutputEventsOf(store, "testA") { Collection<TestOutputEvent> events ->
            [events.size(), events.iterator().hasNext()]
        }

        then:
        result == [0, false]
    }

    /**
     * Writes tests A and B under a root, whose output events are interleaved with each other.
     * Test A alternates between stdout and stderr.
     */
    private Path writeInterleavedOutput(int eventCount) {
        return writeStore { writer ->
            def root = descriptor("root", null)
            def testA = descriptor("testA", root)
            def testB = descriptor("testB", root)
            writer.started(root, new TestStartEvent(0))
            writer.started(testA, new TestStartEvent(0))
            writer.started(testB, new TestStartEvent(0))
            for (int i = 0; i < eventCount; i++) {
                writer.output(testA, new DefaultTestOutputEvent(i, i % 2 == 0 ? StdOut : StdErr, "A" + i))
                writer.output(testB, new DefaultTestOutputEvent(i, StdOut, "B" + i))
            }
            writer.completed(testA, result(), new TestCompleteEvent(1))
            writer.completed(testB, result(), new TestCompleteEvent(1))
            writer.completed(root, result(), new TestCompleteEvent(1))
        }
    }

    private static <T> T withOutputEventsOf(Path storeDir, String testName, Closure<T> action) {
        def store = new SerializableTestResultStore(storeDir)
        def model = TestTreeModel.loadModelFromStores([store])
        def test = model.children.find { it.path.name == testName }
        TestOutputReader reader = store.createOutputReader(TestEventSerializer.create().build(TestOutputEvent))
        try {
            return action(reader.getAllTestOutputEvents(test.perRootInfo[0][0].outputEntries))
        } finally {
            reader.close()
        }
    }

    private Path writeStore(Closure<?> writeAction) {
        def storeDir = tempDir.resolve("store")
        def writer = new SerializableTestResultStore(storeDir).openWriter(0)
        try {
            writeAction(writer)
        } finally {
            writer.close()
        }
        return storeDir
    }

    private TestDescriptorInternal descriptor(String name, TestDescriptorInternal parent) {
        def uniqueId = nextDescriptorId++
        return Mock(TestDescriptorInternal) {
            getId() >> uniqueId
            getName() >> name
            getDisplayName() >> name
            getParent() >> parent
        }
    }

    private TestResult result() {
        return Mock(TestResult) {
            getStartTime() >> 0
            getEndTime() >> 1
            getResultType() >> TestResult.ResultType.SUCCESS
            getAssumptionFailure() >> null
            getFailures() >> []
        }
    }
}
