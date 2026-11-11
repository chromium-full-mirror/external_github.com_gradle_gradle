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

import spock.lang.Specification
import spock.lang.Timeout

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@Timeout(30)
class BoundedCharPipeTest extends Specification {

    def "reads appended text in order and then EOF"() {
        given:
        def pipe = new BoundedCharPipe(16)

        when:
        pipe.appendable.append("hello").append(" world", 0, 3).append('!' as char).append(new StringBuilder("sb")).append(new StringBuilder("xyz"), 1, 2)
        pipe.closeWrite()

        then:
        pipe.reader.text == "hello wo!sby"
    }

    def "reads across chunks into a small buffer"() {
        given:
        def pipe = new BoundedCharPipe(100)
        pipe.appendable.append("abc").append("defgh").append("i")
        pipe.closeWrite()
        def buffer = new char[4]

        expect:
        pipe.reader.read(buffer, 0, 4) == 4
        new String(buffer) == "abcd"
        pipe.reader.read(buffer, 0, 4) == 4
        new String(buffer) == "efgh"
        pipe.reader.read(buffer, 0, 4) == 1
        buffer[0] == 'i' as char
        pipe.reader.read(buffer, 0, 4) == -1
    }

    def "writer blocks when full until reader drains, and accepts text larger than capacity"() {
        given:
        def pipe = new BoundedCharPipe(10)
        def text = (0..<1000).collect { it.toString() }.join(",")
        def big = "x" * 50

        when:
        def written = CompletableFuture.runAsync {
            text.split("(?<=,)").each { pipe.appendable.append(it) }
            pipe.appendable.append(big)
            pipe.closeWrite()
        }
        def read = pipe.reader.text
        written.get(10, TimeUnit.SECONDS)

        then:
        read == text + big
    }

    def "closing the read side fails a blocked writer with the cause"() {
        given:
        def pipe = new BoundedCharPipe(4)
        def failure = new RuntimeException("parser broke")
        def blocked = new CountDownLatch(1)

        when:
        def written = CompletableFuture.runAsync {
            pipe.appendable.append("1234")
            blocked.countDown()
            pipe.appendable.append("5678")
        }
        blocked.await()
        pipe.closeRead(failure)
        written.get(10, TimeUnit.SECONDS)

        then:
        def e = thrown(Exception)
        def cause = e.cause
        cause instanceof IOException
        cause.message == "Pipe closed by reader"
        cause.cause.is(failure)
    }

    def "first close of the read side keeps its failure"() {
        given:
        def pipe = new BoundedCharPipe(4)
        def failure = new RuntimeException("parser broke")

        when:
        pipe.closeRead(failure)
        pipe.reader.close()
        pipe.appendable.append("a")

        then:
        def e = thrown(IOException)
        e.message == "Pipe closed by reader"
        e.cause.is(failure)
    }

    def "cannot append after closing the write side"() {
        given:
        def pipe = new BoundedCharPipe(4)
        pipe.closeWrite()

        when:
        pipe.appendable.append("a")

        then:
        def e = thrown(IOException)
        e.message == "Pipe closed by writer"
    }
}
