/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.skywalking.apm.agent.core.boot;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;

/**
 * AgentPackagePath is a flag and finder to locate the SkyWalking agent.jar. It gets the absolute path of the agent jar.
 * The path is the required metadata for agent core looking up the plugins and toolkit activations. If the lookup
 * mechanism fails, the agent will exit directly.
 */
public class AgentPackagePath {
    private static final ILog LOGGER = LogManager.getLogger(AgentPackagePath.class);

    private static File AGENT_PACKAGE_PATH;

    public static File getPath() throws AgentPackageNotFoundException {
        if (AGENT_PACKAGE_PATH == null) {
            // 返回 skywalking-agent.jar 文件所在的目录 E:\develop\source\sample\source\skywalking-java\skywalking-agent
            AGENT_PACKAGE_PATH = findPath();
        }
        return AGENT_PACKAGE_PATH;
    }

    public static boolean isPathFound() {
        return AGENT_PACKAGE_PATH != null;
    }

    private static File findPath() throws AgentPackageNotFoundException {
        // 将全类名中的.替换成/
        // org/apache/skywalking/apm/agent/core/boot/AgentPackagePath.class
        String classResourcePath = AgentPackagePath.class.getName().replaceAll("\\.", "/") + ".class";
        // 使用 AppClassLoader 加载资源，通常情况下 AgentPackagePath 类是被 AppClassLoader 加载的。
        URL resource = ClassLoader.getSystemClassLoader().getResource(classResourcePath);
        if (resource != null) {
            String urlString = resource.toString();
            //jar:file:/E:/develop/source/sample/source/skywalking-java/skywalking-agent/skywalking-agent.jar!/org/apache/skywalking/apm/agent/core/boot/AgentPackagePath.class
            LOGGER.debug("The beacon class location is {}.", urlString);

            // 判断 url 中是否包含!，如果包含则说明 AgentPackagePath.class 是包含在jar中。
            int insidePathIndex = urlString.indexOf('!');
            boolean isInJar = insidePathIndex > -1;

            if (isInJar) {
                // file:/E:/develop/source/sample/source/skywalking-java/skywalking-agent/skywalking-agent.jar
                urlString = urlString.substring(urlString.indexOf("file:"), insidePathIndex);
                File agentJarFile = null;
                try {
                    // E:\develop\source\sample\source\skywalking-java\skywalking-agent\skywalking-agent.jar
                    agentJarFile = new File(new URL(urlString).toURI());
                } catch (MalformedURLException | URISyntaxException e) {
                    LOGGER.error(e, "Can not locate agent jar file by url:" + urlString);
                }
                if (agentJarFile.exists()) {
                    // 返回 skywalking-agent.jar 文件所在的目录
                    return agentJarFile.getParentFile();
                }
            } else {
                int prefixLength = "file:".length();
                String classLocation = urlString.substring(
                    prefixLength, urlString.length() - classResourcePath.length());
                return new File(classLocation);
            }
        }

        LOGGER.error("Can not locate agent jar file.");
        throw new AgentPackageNotFoundException("Can not locate agent jar file.");
    }

}
