/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.as.test.manualmode.transaction;

import jakarta.annotation.PostConstruct;
import jakarta.ejb.Singleton;
import jakarta.ejb.Startup;
import jakarta.ejb.TransactionManagement;
import jakarta.ejb.TransactionManagementType;
import jakarta.transaction.UserTransaction;
import javax.naming.InitialContext;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

@Singleton
@Startup
@TransactionManagement(TransactionManagementType.BEAN)
public class LongRunningTransactionBean {

    private static final String MARKER_DIR = "target" + File.separator + "graceful-shutdown-markers";
    static final String MARKER_FILE = MARKER_DIR + File.separator + "drained-result.txt";

    @PostConstruct
    public void init() {
        Path dir = Paths.get(MARKER_DIR);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create marker directory", e);
        }

        int sleepSeconds = Integer.getInteger("test.txn.sleep.seconds", 5);
        Thread thread = new Thread(() -> {
            try {
                InitialContext ctx = new InitialContext();
                UserTransaction ut = (UserTransaction) ctx.lookup("java:jboss/UserTransaction");
                ut.begin();
                Thread.sleep(sleepSeconds * 1000L);
                ut.commit();
                writeMarker("DRAINED");
            } catch (Exception e) {
                writeMarker("ERROR:" + e.getMessage());
            }
        }, "long-running-txn-thread");
        thread.setDaemon(true);
        thread.start();
    }

    private void writeMarker(String content) {
        try {
            Files.writeString(Paths.get(MARKER_FILE), content,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            // Can't throw from background thread — just log to stdout
            System.err.println("Failed to write marker file: " + e.getMessage());
        }
    }
}
