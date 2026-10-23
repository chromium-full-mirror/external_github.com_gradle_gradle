/*
 * Copyright 2016 the original author or authors.
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

package org.gradle.internal.logging.console

import org.gradle.internal.logging.text.StyledTextOutput
import org.gradle.internal.nativeintegration.console.ConsoleMetaData
import org.gradle.internal.operations.OperationIdentifier
import spock.lang.Issue
import spock.lang.Specification
import spock.lang.Subject

class DefaultWorkInProgressFormatterTest extends Specification {
    def consoleMetaData = Mock(ConsoleMetaData)
    @Subject statusBarFormatter = new DefaultWorkInProgressFormatter(consoleMetaData)

    def "formats operations"() {
        given:
        def op1 = new ProgressOperation("STATUS_1", "VARIANT_CATEGORY", new OperationIdentifier(1), null)
        def op2 = new ProgressOperation(null,  null, new OperationIdentifier(2), op1)
        def op3 = new ProgressOperation("STATUS_2", "VARIANT_CATEGORY", new OperationIdentifier(3), op2)

        expect:
        statusBarFormatter.format(op3).first().text == "> STATUS_1 > STATUS_2"
        statusBarFormatter.format(op2).first().text == "> STATUS_1"
        statusBarFormatter.format(op1).first().text == "> STATUS_1"
    }

    @Issue("https://github.com/gradle/gradle/issues/39544")
    def "adds the number of completed work actions to the line of their parent, from its second work action"() {
        given:
        def task = new ProgressOperation(":work", "VARIANT_CATEGORY", new OperationIdentifier(1), null)
        def first = new ProgressOperation("Executing work action A", "VARIANT_CATEGORY", new OperationIdentifier(2), task, true)
        task.addChild(first)

        expect:
        statusBarFormatter.format(task).first().text == "> :work"

        when:
        task.addChild(new ProgressOperation("Executing work action B", "VARIANT_CATEGORY", new OperationIdentifier(3), task, true))

        then:
        statusBarFormatter.format(task).first().text == "> :work > 0 work actions completed"
        statusBarFormatter.format(first).first().text == "> :work > Executing work action A"

        when:
        task.workActionCompleted()

        then:
        statusBarFormatter.format(task).first().text == "> :work > 1 work action completed"

        when:
        task.workActionCompleted()

        then:
        statusBarFormatter.format(task).first().text == "> :work > 2 work actions completed"
    }

    def "trims output to one less than the max console width"() {
        given:
        def operation = new ProgressOperation("these are more than 10 characters", "VARIANT_CATEGORY", new OperationIdentifier(1), null)

        when:
        _ * consoleMetaData.getCols() >> 10

        then:
        statusBarFormatter.format(operation).first().text == "> these a"
    }

    def "placeholder is used when message is empty"() {
        given:
        def operation = new ProgressOperation(null, null, new OperationIdentifier(1), null)

        expect:
        statusBarFormatter.format(operation).first().text == "> IDLE"
        statusBarFormatter.format(operation).first().style == StyledTextOutput.Style.Normal
    }
}
