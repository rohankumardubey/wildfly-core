/*
 * JBoss, Home of Professional Open Source
 *
 * Copyright 2019 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wildfly.extension.elytron;

/**
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
final class JdkUtils {

    private static final String javaSpecVersion = System.getProperty("java.specification.version");
    private static final String javaVendor = System.getProperty("java.vendor");

    private JdkUtils() {}

    static int getJavaSpecVersion() {
        return Integer.parseInt(javaSpecVersion);
    }

    static boolean isIbmJdk() {
        return javaVendor.startsWith("IBM");
    }

}
