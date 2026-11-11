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

/**
 * Renders a custom entry into the generic HTML test report.
 *
 * <p>
 * Each renderer gets its own tab on every page of the report that is generated. Pages are only generated for
 * test containers, failed or skipped tests, and tests with output or metadata. When a container ran more than
 * once (e.g. in multiple test workers), the renderer is invoked once per run.
 * </p>
 *
 * <p>
 * Report pages are generated in parallel, so renderers may be invoked concurrently from multiple threads.
 * </p>
 *
 * <p>
 * More ways of generating report entries may be added in the future.
 * </p>
 *
 * @see HtmlEmittingTestReportEntryRenderer
 * @since 9.10.0
 */
@Incubating
@NullMarked
public interface TestReportEntryRenderer {
    /**
     * The name of the tab this renderer's entry is shown in.
     *
     * <p>
     * Must not be blank, must not have leading or trailing whitespace, must be unique among the renderers of a report,
     * and must not clash with the names of the built-in tabs.
     * </p>
     *
     * @return the tab name
     * @since 9.10.0
     */
    String getTabName();
}
