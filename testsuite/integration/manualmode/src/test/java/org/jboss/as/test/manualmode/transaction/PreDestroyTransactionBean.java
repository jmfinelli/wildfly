/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.as.test.manualmode.transaction;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.ejb.EJB;
import jakarta.ejb.Singleton;
import jakarta.ejb.Startup;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

@Singleton
@Startup
public class PreDestroyTransactionBean {

    private static final String MARKER_DIR = "target" + File.separator + "graceful-shutdown-markers";
    static final String MARKER_FILE = MARKER_DIR + File.separator + "predestroy-result.txt";

    @EJB
    private TransactionHelper helper;

    @PostConstruct
    public void init() {
        Path dir = Paths.get(MARKER_DIR);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create marker directory", e);
        }
    }

    @PreDestroy
    public void cleanup() {
        try {
            helper.doWork();
            writeMarker("SUCCESS");
        } catch (Exception e) {
            writeMarker("FAILURE:" + e.getMessage());
        }
    }

    private void writeMarker(String content) {
        try {
            Files.writeString(Paths.get(MARKER_FILE), content,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write marker file", e);
        }
    }
}
