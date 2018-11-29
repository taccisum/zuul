/*
 * Copyright 2013 Netflix, Inc.
 *
 *      Licensed under the Apache License, Version 2.0 (the "License");
 *      you may not use this file except in compliance with the License.
 *      You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 *      Unless required by applicable law or agreed to in writing, software
 *      distributed under the License is distributed on an "AS IS" BASIS,
 *      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *      See the License for the specific language governing permissions and
 *      limitations under the License.
 */
package com.netflix.zuul;

/**
 * TODO:: app info
 * Metadata about the Zuul instance/ application name and "stack"
 * @author Mikey Cohen
 * Date: 2/15/13
 * Time: 1:56 PM
 */
public class ZuulApplicationInfo {
    private static String applicationName;
    private static String stack;

    public static String getApplicationName() {
        return applicationName;
    }

    public static void setApplicationName(String applicationName) {
        ZuulApplicationInfo.applicationName = applicationName;
    }

    public static String getStack() {
        return stack;
    }

    public static void setStack(String stack) {
        ZuulApplicationInfo.stack = stack;
    }
}
