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

import org.gradle.api.tasks.testing.HtmlEmittingTestReportEntryRenderer;
import org.gradle.api.tasks.testing.TestReportEntryRenderingContext;
import org.gradle.internal.html.SimpleHtmlWriter;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.parser.Parser;
import org.jsoup.parser.StreamParser;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * Streams the output of a {@link HtmlEmittingTestReportEntryRenderer} into a report page, making it well-formed on the way.
 *
 * <p>
 * The renderer writes on the calling thread into a bounded pipe, while jsoup's {@link StreamParser} parses the other end on
 * a pooled thread. jsoup only pulls from a {@link java.io.Reader}, so the parsing must happen on another thread.
 * </p>
 *
 * <p>
 * Each top-level node is written to the page and dropped from the parse tree as soon as it is complete,
 * so only the current top-level node is held in memory.
 * </p>
 *
 * <p>
 * The page writer is only used by the parsing thread between submitting it and joining it, during which the calling thread does not touch it.
 * </p>
 */
final class StreamingHtmlEntryWriter {
    private static final int PIPE_CAPACITY = 64 * 1024;

    private final TestReportEntryRenderingExecutor executor;

    StreamingHtmlEntryWriter(TestReportEntryRenderingExecutor executor) {
        this.executor = executor;
    }

    void write(
        HtmlEmittingTestReportEntryRenderer renderer,
        TestReportEntryRenderingContext context,
        SimpleHtmlWriter htmlWriter
    ) throws IOException {
        Appendable pageOut = htmlWriter.startRawMarkup();
        BoundedCharPipe pipe = new BoundedCharPipe(PIPE_CAPACITY);
        Future<?> parsing = executor.submit(() -> {
            parse(pipe, pageOut);
            return null;
        });

        Throwable renderFailure = null;
        try {
            renderer.render(context, pipe.getAppendable());
        } catch (Throwable t) {
            renderFailure = t;
        } finally {
            // Let the parser finish what it has, even if the renderer failed, so the page stays well-formed
            pipe.closeWrite();
        }

        Throwable parseFailure = joinUninterruptibly(parsing);
        if (parseFailure != null) {
            // The renderer's failure is likely just a symptom of the pipe being closed by the parser
            if (renderFailure != null) {
                parseFailure.addSuppressed(renderFailure);
            }
            throw new GenericHtmlReportGenerationException(
                String.format("Could not parse the HTML of custom test report entry '%s' rendered by %s.", renderer.getTabName(), renderer.getClass().getName()),
                parseFailure
            );
        }
        if (renderFailure != null) {
            throw new GenericHtmlReportGenerationException(
                String.format("Could not render custom test report entry '%s' with %s.", renderer.getTabName(), renderer.getClass().getName()),
                renderFailure
            );
        }
    }

    private static void parse(BoundedCharPipe pipe, Appendable pageOut) throws IOException {
        try (StreamParser parser = new StreamParser(Parser.htmlParser())) {
            try {
                parser.parseFragment(pipe.getReader(), new Element("div"), "");
                Document document = parser.document();
                document.outputSettings().prettyPrint(false);
                Element fragmentRoot = document.child(0);

                Iterator<Element> completedElements = parser.iterator();
                while (completedElements.hasNext()) {
                    Element element = completedElements.next();
                    // Only flush top-level elements, nested ones are flushed as part of their top-level element
                    if (element.parent() == fragmentRoot) {
                        flushUpTo(fragmentRoot, element, pageOut);
                    }
                }
                // Flush anything after the last top-level element, like trailing text
                for (Node node : new ArrayList<>(parser.completeFragment())) {
                    node.outerHtml(pageOut);
                    node.remove();
                }
            } catch (Throwable t) {
                // Unblock the renderer with the cause before closing the parser, which closes the pipe's reader without a cause
                pipe.closeRead(t);
                throw t;
            }
        } finally {
            // Unblock the renderer if it is still writing, a no-op if the pipe was already closed
            pipe.closeRead(null);
        }
    }

    /**
     * Writes and removes all top-level nodes up to and including the given one. This includes text and comments before it,
     * which are never yielded by the parser as they aren't elements.
     */
    private static void flushUpTo(Element fragmentRoot, Element lastNode, Appendable pageOut) {
        List<Node> nodes = new ArrayList<>(fragmentRoot.childNodes());
        for (Node node : nodes) {
            // Serialize before removing, as removing the node detaches it from the document's output settings
            node.outerHtml(pageOut);
            node.remove();
            if (node == lastNode) {
                return;
            }
        }
    }

    @Nullable
    private static Throwable joinUninterruptibly(Future<?> future) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    future.get();
                    return null;
                } catch (InterruptedException e) {
                    // We must wait for the parser to finish before touching the page writer again
                    interrupted = true;
                } catch (ExecutionException e) {
                    return e.getCause();
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
