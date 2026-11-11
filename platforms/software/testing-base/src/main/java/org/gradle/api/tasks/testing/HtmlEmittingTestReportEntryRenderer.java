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

import java.io.IOException;

/**
 * Renders a custom entry into the generic HTML test report as raw HTML.
 *
 * <p>
 * The HTML is parsed as it is written, and only made well-formed, so elements can't be left open into the rest of the page.
 * It is otherwise not sanitized, so elements like {@code <script>} and {@code <style>} are kept, and can still affect the rest of the page.
 * </p>
 *
 * <p>
 * The HTML is streamed into the report one top-level element at a time. To keep memory usage low, prefer emitting
 * many top-level elements over wrapping everything in a single element, as a top-level element is held in memory
 * until it is complete.
 * </p>
 *
 * @since 9.10.0
 */
@Incubating
@NullMarked
public interface HtmlEmittingTestReportEntryRenderer extends TestReportEntryRenderer {
    /**
     * Renders the entry for a test.
     *
     * @param context the test to render
     * @param htmlOut where to write the HTML for the entry
     * @throws IOException if writing to {@code htmlOut} fails
     * @since 9.10.0
     */
    void render(TestReportEntryRenderingContext context, Appendable htmlOut) throws IOException;
}
