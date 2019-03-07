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

package org.gradle.launcher.daemon

import org.gradle.integtests.fixtures.TargetCoverage
import org.gradle.integtests.fixtures.executer.GradleHandle
import org.gradle.launcher.daemon.fixtures.DaemonMultiJdkIntegrationTest
import org.gradle.launcher.daemon.server.health.DaemonMemoryStatus
import org.gradle.soak.categories.SoakTest
import org.junit.experimental.categories.Category
import spock.lang.Unroll

@Category(SoakTest)
@TargetCoverage({DaemonPerformanceMonitoringCoverage.ALL_VERSIONS})
class DaemonPerformanceMonitoringSoakTest extends DaemonMultiJdkIntegrationTest {
    int maxBuilds
    String heapSize
    int leakRate
    Closure setupBuildScript

    def setup() {
        buildFile << "${logJdk()}"

        // Set JVM args for GC
        String jvmArgs = ""
        if (file('gradle.properties').exists()) {
            jvmArgs = file("gradle.properties").getProperties().getOrDefault("org.gradle.jvmargs", "")
        }
        file("gradle.properties").writeProperties(
            "org.gradle.java.home": jdk.javaHome.absolutePath,
            "org.gradle.jvmargs": jvmArgs + " " + version.gc.jvmArgs
        )
    }

    @Unroll
    def "when build leaks slowly daemon is eventually expired (heap: #heap)"() {
        when:
        setupBuildScript = tenuredHeapLeak
        maxBuilds = builds
        heapSize = heap
        leakRate = rate

        then:
        daemonIsExpiredEagerly()

        where:
        builds | heap    | rate
        45     | "200m"  | 600
        40     | "1024m" | 4000
    }

    private boolean daemonIsExpiredEagerly() {
        def dataFile = file("stats")
        setupBuildScript()
        int newDaemons = 0
        try {
            for (int i = 0; i < maxBuilds; i++) {
                executer.noExtraLogging()
                executer.withBuildJvmOpts("-D${DaemonMemoryStatus.ENABLE_PERFORMANCE_MONITORING}=true", "-Xms128m", "-XX:MaxMetaspaceSize=${heapSize}", "-Xmx${heapSize}", "-Dorg.gradle.daemon.performance.logging=true")
                GradleHandle gradle = executer.start()
                gradle.waitForExit()
                if (gradle.standardOutput ==~ /(?s).*Starting build in new daemon \[memory: [0-9].*/) {
                    newDaemons++;
                }
                if (newDaemons > 1) {
                    return true
                }
                def lines = gradle.standardOutput.readLines()
                dataFile << lines[lines.findLastIndexOf { it.startsWith "Starting" }]
                dataFile << "  " + lines[lines.findLastIndexOf { it.contains "Total time:" }]
                dataFile << "\n"
            }
        } finally {
            println dataFile.text
        }
        return false
    }

    private final Closure tenuredHeapLeak = {
        buildFile << """
            class State {
                static int x
                static map = [:]
            }
            try {
                State.x++

                //simulate normal collectible objects
                5000.times {
                    State.map.put(it, "foo" * ${leakRate})
                }

                //simulate the leak
                1000.times {
                    State.map.put(UUID.randomUUID(), "foo" * ${leakRate})
                }

                println "Build: " + State.x
            } catch(OutOfMemoryError e) {
                // TeamCity recognizes this message as build failures if it occurs in build log
                throw new OutOfMemoryError(e?.message?.replace(' ', '_'))
            }
        """
    }

    private final Closure greedyBuildNoLeak = {
        buildFile << """
            Map map = [:]

            //simulate normal collectible objects
            5000.times {
                map.put(it, "foo" * ${leakRate})
            }
        """
    }

    String logJdk() {
        return """logger.warn("Build is running with JDK: \${System.getProperty('java.home')}")"""
    }
}
