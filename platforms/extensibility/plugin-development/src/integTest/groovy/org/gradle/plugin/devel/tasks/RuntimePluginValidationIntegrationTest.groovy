/*
 * Copyright 2019 the original author or authors.
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

package org.gradle.plugin.devel.tasks

import org.gradle.api.problems.Severity
import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import org.gradle.internal.reflect.validation.ValidationMessageChecker
import spock.lang.Issue

class RuntimePluginValidationIntegrationTest extends AbstractIntegrationSpec implements RuntimePluginValidationTrait, ValidationMessageChecker {

    def "supports recursive types"() {
        groovyTaskSource << """
            import org.gradle.api.*
            import org.gradle.api.tasks.*

            class MyTask extends DefaultTask {
                @Nested
                Tree tree = new Tree(
                        left: new Tree([:]),
                        right: new Tree([:])
                    )

                public static class Tree {
                    @Optional @Nested
                    Tree left

                    @Optional @Nested
                    Tree right

                    String nonAnnotated
                }

                @TaskAction void execute() {}
            }
        """

        expect:
        assertValidationFailsWith(5)
        verifyMissingAnnotationProblems('nonAnnotated', ['tree.left.left', 'tree.left', 'tree.left.right', 'tree', 'tree.right'])
    }

    def "detects problems with file inputs"() {
        file("input.txt").text = "input"
        file("input").createDir()

        javaTaskSource << """
            import org.gradle.api.*;
            import org.gradle.api.file.*;
            import org.gradle.api.tasks.*;
            import java.util.Set;
            import java.util.Collections;
            import java.io.File;
            import java.nio.file.Path;

            @CacheableTask
            public abstract class MyTask extends DefaultTask {
                @javax.inject.Inject
                org.gradle.api.internal.file.FileResolver fileResolver;

                @InputDirectory
                @Optional
                public File getInputDirectory() {
                    return new File("input");
                }

                @InputFile
                public File getInputFile() {
                    return new File("input.txt");
                }

                @InputFiles
                public Set<File> getInputFiles() {
                    return Collections.emptySet();
                }

                @Input
                public File getFile() {
                    return new File("some-file");
                }

                @Input
                public FileCollection getFileCollection() {
                    return getProject().files();
                }

                @Input
                public Path getFilePath() {
                    return new File("some-file").toPath();
                }

                @Input
                public FileTree getFileTree() {
                    return getProject().files().getAsFileTree();
                }

                @TaskAction
                public void doStuff() { }
            }
        """

        expect:
        executer.withArgument("-Dorg.gradle.internal.max.validation.errors=10")
        // Pre-Validate errors halt execution before further problems are detected
        assertValidationFailsWith(4)
        // Received problems are sorted by id and then by label, so the properties are in label order
        [file: 'File', fileCollection: 'FileCollection', filePath: 'Path', fileTree: 'FileTree'].eachWithIndex { property, type, index ->
            verifyAll(receivedProblem(index)) {
                severity == Severity.ERROR
                fqid == 'Gradle:Plugin Validation:Incorrect use of @Input annotation'
                contextualLabel == "Type 'MyTask' property '$property' has @Input annotation used on property of type '$type'"
                details == "A property of type '$type' annotated with @Input cannot determine how to interpret the file"
                solutions == [
                    'Annotate with @InputFile for regular files',
                    'Annotate with @InputFiles for collections of files',
                    'If you want to track the path, return File.absolutePath as a String and keep @Input',
                ]
                additionalData.asMap == [
                    'typeName' : 'MyTask',
                    'propertyName' : property,
                ]
                originLocations == []
                contextualLocations == []
            }
        }
    }

    //@IgnoreRest
    def "detects problems on nested collections"() {
        javaTaskSource << """
            import org.gradle.api.*;
            import org.gradle.api.model.*;
            import org.gradle.api.provider.*;
            import org.gradle.api.tasks.*;
            import org.gradle.work.*;
            import java.util.*;
            import java.io.File;
            import javax.inject.Inject;

            @DisableCachingByDefault(because = "test task")
            public abstract class MyTask extends DefaultTask {
                @Inject
                protected abstract ObjectFactory getObjects();

                @Nested
                public Options getOptions() {
                    return new Options();
                }

                @Nested
                public List<Options> getOptionsList() {
                    return Arrays.asList(new Options());
                }

                @Nested
                public Iterable<Options> getIterableOptions() {
                    return Arrays.asList(new Options());
                }

                @Nested
                public Iterable<Iterable<Options>> getDoubleIterableOptions() {
                    return Arrays.asList(Arrays.asList(new Options()));
                }

                @Nested
                public Map<String, Options> getMappedOptions() {
                    return Collections.singletonMap("alma", new Options());
                }

                @Nested
                public Iterable<Map<String, Iterable<Options>>> getIterableMappedOptions() {
                    return Arrays.asList(Collections.singletonMap("alma", Arrays.asList(new Options())));
                }

                @Nested
                public Provider<Options> getProvidedOptions() {
                    return getObjects().property(Options.class).convention(new Options());
                }

                @Nested
                public Iterable<NamedBean> getNamedIterable() {
                    return Arrays.asList(new NamedBean());
                }

                @Nested
                public AnnotatedList getAnnotatedList() {
                    return new AnnotatedList();
                }

                public static class Options {
                    @Input
                    public String getGood() {
                        return "good";
                    }

                    public String getNotAnnotated() {
                        return null;
                    }
                }

                public static class NamedBean implements Named {
                    @Input
                    public String getGood() {
                        return "good";
                    }

                    public String getNotAnnotated() {
                        return null;
                    }

                    @Internal
                    public String getName() {
                        return "tibor";
                    }
                }

                // Does not validate the type parameter of extended collection
                // because it has annotated properties
                public static class AnnotatedList extends ArrayList<Options> {
                    public AnnotatedList() {
                        add(new Options());
                    }

                    @Input
                    public String getGood() {
                        return "good";
                    }
                }

                @TaskAction
                public void doStuff() { }
            }
        """

        expect:
        executer.withArgument("-Dorg.gradle.internal.max.validation.errors=10")
        assertValidationFailsWith(8)
        verifyMissingAnnotationProblems('notAnnotated', [
            'doubleIterableOptions.$0.$0',
            'iterableMappedOptions.$0.alma.$0',
            'iterableOptions.$0',
            'mappedOptions.alma',
            'namedIterable.tibor$0',
            'options',
            'optionsList.$0',
            'providedOptions',
        ])
    }

    @Issue("https://github.com/gradle/gradle/issues/24444")
    def "value not set because it is derived from a property whose value cannot be configured"() {
        enableProblemsApiCheck()
        groovyTaskSource << """
            import org.gradle.api.*;
            import org.gradle.api.model.*;
            import org.gradle.api.provider.*;
            import org.gradle.api.tasks.*;
            import org.gradle.work.*;

            @DisableCachingByDefault(because = "test task")
            abstract class MyTask extends DefaultTask {

                @Input
                @Optional
                abstract Property<String> getGreeting();

                @Input
                Provider<String> message = greeting.map(it -> it + " from Gradle");

                @TaskAction
                void printMessage() {
                    logger.quiet(message().get());
                }
            }
        """

        when:
        fails "run"

        then:
        failure.assertHasDescription("A problem was found with the configuration of task ':run' (type 'MyTask').")
        verifyAll(receivedProblem) {
            severity == Severity.ERROR
            fqid == 'Gradle:Build Definition:Value not set'
            definition.id.displayName == 'Value not set'
            contextualLabel == "Type 'MyTask' property 'message' doesn't have a configured value"
            details == "This property isn't marked as optional and no value has been configured"
            solutions == [
                "The value of 'message' is calculated, make sure a valid value can be calculated",
                "Mark property 'message' as optional",
            ]
            additionalData.asMap == [
                'typeName': 'MyTask',
                'propertyName': 'message',
            ]
            definition.documentationLink.url == "https://docs.gradle.org/${distribution.version.version}/userguide/validation_problems.html#value_not_set"
        }
    }

    /**
     * Verifies that the received problems are exactly one missing-annotation problem per parent property, in the given order.
     * Received problems are sorted by id and then by label, so the parents must be in label order.
     */
    private void verifyMissingAnnotationProblems(String property, List<String> parentProperties) {
        parentProperties.eachWithIndex { parent, index ->
            verifyAll(receivedProblem(index)) {
                severity == Severity.ERROR
                fqid == 'Gradle:Plugin Validation:Missing annotation'
                contextualLabel == "Type 'MyTask' property '${parent}.${property}' is missing an input or output annotation"
                details == 'Properties must be annotated so that Gradle knows how to handle them during up-to-date checking'
                solutions == [
                    'Add an input or output annotation',
                    'Mark it as @Internal',
                ]
                additionalData.asMap == [
                    'propertyName' : property,
                    'parentPropertyName' : parent,
                    'typeName' : 'MyTask',
                ]
                originLocations == []
                contextualLocations == []
            }
        }
    }
}
