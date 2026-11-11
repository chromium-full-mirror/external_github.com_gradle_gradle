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

package org.gradle.api.tasks.testing;

import org.gradle.api.Incubating;
import org.jspecify.annotations.NullMarked;

import java.util.Collection;

/**
 * Information about the test being rendered by a {@link TestReportEntryRenderer}.
 *
 * <p>
 * The context is only valid during the call to the renderer.
 * </p>
 *
 * @since 9.10.0
 */
@Incubating
@NullMarked
public interface TestReportEntryRenderingContext {
    /**
     * The descriptor of the test being rendered.
     *
     * <p>
     * The parent of the descriptor is not available.
     * </p>
     *
     * @return the test descriptor
     * @since 9.10.0
     */
    TestDescriptor getTestDescriptor();

    /**
     * The result of the test being rendered.
     *
     * <p>
     * Failures are reconstructed from the stored test results, so only their {@linkplain TestFailure#getDetails() details} are available.
     * {@link TestFailure#getRawFailure()} and {@link TestResult#getException()} return {@code null},
     * {@link TestResult#getExceptions()} is empty, and causes of failures are not available.
     * </p>
     *
     * @return the test result
     * @since 9.10.0
     */
    TestResult getTestResult();

    /**
     * The output events of the test being rendered, in the order they were emitted.
     *
     * <p>
     * The events are read lazily from disk on iteration and are not retained in memory.
     * </p>
     *
     * @return the output events
     * @since 9.10.0
     */
    Collection<TestOutputEvent> getOutputEvents();

    /**
     * The metadata events of the test being rendered.
     *
     * @return the metadata events
     * @since 9.10.0
     */
    Collection<TestMetadataEvent> getMetadataEvents();
}
