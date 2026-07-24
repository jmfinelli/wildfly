/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.as.test.manualmode.transaction;

import static org.jboss.as.controller.client.helpers.Operations.createAddOperation;
import static org.jboss.as.controller.client.helpers.Operations.createAddress;
import static org.jboss.as.controller.client.helpers.Operations.createReadAttributeOperation;
import static org.jboss.as.controller.client.helpers.Operations.createWriteAttributeOperation;
import static org.jboss.as.controller.client.helpers.Operations.getFailureDescription;
import static org.jboss.as.controller.client.helpers.Operations.isSuccessfulOutcome;
import static org.jboss.as.test.shared.PermissionUtils.createPermissionsXmlAsset;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FilePermission;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.PropertyPermission;
import java.util.stream.Stream;

import org.jboss.arquillian.container.test.api.ContainerController;
import org.jboss.arquillian.container.test.api.Deployer;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.container.test.api.TargetsContainer;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.test.shared.ServerReload;
import org.jboss.as.test.shared.TestSuiteEnvironment;
import org.jboss.as.test.shared.logging.LoggingUtil;
import org.jboss.dmr.ModelNode;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(Arquillian.class)
@RunAsClient
public class GracefulShutdownTransactionTestCase {

    private static final String CONTAINER = "default-jbossas";
    private static final String PREDESTROY_DEPLOYMENT = "predestroy-test";
    private static final String LONGRUNNING_DEPLOYMENT = "longrunning-test";

    private static final String MARKER_DIR = "target" + File.separator + "graceful-shutdown-markers";
    private static final Path MARKER_DIR_PATH = Paths.get(MARKER_DIR);

    private static final ModelNode TXN_SUBSYSTEM_ADDRESS =
        createAddress("subsystem", "transactions");

    private Path serverLogPath;
    private long logBaseline;

    @ArquillianResource
    private ContainerController controller;

    @ArquillianResource
    private Deployer deployer;

    @Deployment(name = PREDESTROY_DEPLOYMENT, managed = false, testable = false)
    @TargetsContainer(CONTAINER)
    public static Archive<?> createPreDestroyDeployment() {
        String markerDirAbsolute = Paths.get(MARKER_DIR).toAbsolutePath().toString();
        return ShrinkWrap.create(JavaArchive.class, PREDESTROY_DEPLOYMENT + ".jar")
            .addClasses(PreDestroyTransactionBean.class, TransactionHelper.class)
            .addAsManifestResource(createPermissionsXmlAsset(
                new FilePermission(markerDirAbsolute, "read,write"),
                new FilePermission(markerDirAbsolute + File.separator + "-", "read,write,delete")
            ), "permissions.xml");
    }

    @Deployment(name = LONGRUNNING_DEPLOYMENT, managed = false, testable = false)
    @TargetsContainer(CONTAINER)
    public static Archive<?> createLongRunningDeployment() {
        String markerDirAbsolute = Paths.get(MARKER_DIR).toAbsolutePath().toString();
        return ShrinkWrap.create(JavaArchive.class, LONGRUNNING_DEPLOYMENT + ".jar")
            .addClasses(LongRunningTransactionBean.class)
            .addAsManifestResource(createPermissionsXmlAsset(
                new FilePermission(markerDirAbsolute, "read,write"),
                new FilePermission(markerDirAbsolute + File.separator + "-", "read,write,delete"),
                new PropertyPermission("test.txn.sleep.seconds", "read")
            ), "permissions.xml");
    }

    @After
    public void cleanup() throws Exception {
        if (controller.isStarted(CONTAINER)) {
            try { deployer.undeploy(PREDESTROY_DEPLOYMENT); } catch (Exception ignored) {}
            try { deployer.undeploy(LONGRUNNING_DEPLOYMENT); } catch (Exception ignored) {}
            controller.stop(CONTAINER);
        }
        cleanMarkerDirectory();
    }

    @Test
    public void testPreDestroyTransactionSucceedsDuringShutdown() throws Exception {
        controller.start(CONTAINER);
        deployer.deploy(PREDESTROY_DEPLOYMENT);
        controller.stop(CONTAINER);

        Path markerFile = Paths.get(PreDestroyTransactionBean.MARKER_FILE);
        assertTrue("Marker file should exist: " + markerFile, Files.exists(markerFile));
        String result = Files.readString(markerFile).trim();
        assertEquals("EJB @PreDestroy with REQUIRES_NEW transaction should succeed during shutdown",
            "SUCCESS", result);
    }

    @Test
    public void testGracefulShutdownTimeoutAttributeConfiguration() throws Exception {
        controller.start(CONTAINER);
        try (ModelControllerClient client = TestSuiteEnvironment.getModelControllerClient()) {
            // Read default value
            ModelNode result = readAttribute(client, "graceful-shutdown-timeout");
            assertEquals("Default graceful-shutdown-timeout should be 300", 300, result.asInt());

            // Write valid value
            ModelNode writeResult = writeAttribute(client, "graceful-shutdown-timeout", 60);
            assertTrue("Write of 60 should succeed: " + getFailureDescription(writeResult),
                isSuccessfulOutcome(writeResult));

            // Read back
            result = readAttribute(client, "graceful-shutdown-timeout");
            assertEquals("Attribute should be updated to 60", 60, result.asInt());

            // Write negative value — should fail validation
            ModelNode rejectResult = writeAttribute(client, "graceful-shutdown-timeout", -1);
            assertFalse("Write of -1 should be rejected by validation", isSuccessfulOutcome(rejectResult));

            // Restore default
            ModelNode restoreResult = writeAttribute(client, "graceful-shutdown-timeout", 300);
            assertTrue("Restore to 300 should succeed", isSuccessfulOutcome(restoreResult));
        }
        controller.stop(CONTAINER);
    }

    @Test
    public void testInFlightTransactionsDrainBeforeShutdown() throws Exception {
        controller.start(CONTAINER);
        try (ModelControllerClient client = TestSuiteEnvironment.getModelControllerClient()) {
            recordLogBaseline(client);
            writeAttribute(client, "graceful-shutdown-timeout", 30);
            setSystemProperty(client, "test.txn.sleep.seconds", "5");
        }
        deployer.deploy(LONGRUNNING_DEPLOYMENT);
        Thread.sleep(1000); // allow background thread to start transaction
        controller.stop(CONTAINER);

        Path markerFile = Paths.get(LongRunningTransactionBean.MARKER_FILE);
        assertTrue("DRAINED marker file should exist", Files.exists(markerFile));
        String result = Files.readString(markerFile).trim();
        assertEquals("Transaction should have committed before server exit", "DRAINED", result);

        assertTrue("Server log should contain WFLYTX0049 (all in-flight transactions terminated)",
            serverLogContainsSinceBaseline("WFLYTX0049"));
    }

    @Test
    public void testTimeoutExpirySkipsRecoverySuspension() throws Exception {
        controller.start(CONTAINER);
        try (ModelControllerClient client = TestSuiteEnvironment.getModelControllerClient()) {
            recordLogBaseline(client);
            writeAttribute(client, "graceful-shutdown-timeout", 2);
            setSystemProperty(client, "test.txn.sleep.seconds", "30");
        }
        deployer.deploy(LONGRUNNING_DEPLOYMENT);
        Thread.sleep(1000); // allow background thread to start transaction
        controller.stop(CONTAINER);

        assertTrue("Server log should contain WFLYTX0050 (timed out waiting for transactions)",
            serverLogContainsSinceBaseline("WFLYTX0050"));
    }

    @Test
    public void testWaitForeverWithTimeoutZero() throws Exception {
        controller.start(CONTAINER);
        try (ModelControllerClient client = TestSuiteEnvironment.getModelControllerClient()) {
            recordLogBaseline(client);
            writeAttribute(client, "graceful-shutdown-timeout", 0);
            setSystemProperty(client, "test.txn.sleep.seconds", "5");
        }
        deployer.deploy(LONGRUNNING_DEPLOYMENT);
        Thread.sleep(1000); // allow background thread to start transaction
        controller.stop(CONTAINER);

        Path markerFile = Paths.get(LongRunningTransactionBean.MARKER_FILE);
        assertTrue("DRAINED marker file should exist for timeout=0", Files.exists(markerFile));
        String result = Files.readString(markerFile).trim();
        assertEquals("Transaction should have completed with timeout=0", "DRAINED", result);

        assertTrue("Server log should contain WFLYTX0049 (all in-flight transactions terminated)",
            serverLogContainsSinceBaseline("WFLYTX0049"));
        assertFalse("Server log should NOT contain WFLYTX0050 (timeout warning) when timeout=0",
            serverLogContainsSinceBaseline("WFLYTX0050"));
    }

    @Test
    public void testRecoveryGracefulShutdownWaitMode() throws Exception {
        controller.start(CONTAINER);
        try (ModelControllerClient client = TestSuiteEnvironment.getModelControllerClient()) {
            writeAttribute(client, "transactions-recovery-graceful-shutdown", "wait");
            ServerReload.reloadIfRequired(client);
            recordLogBaseline(client);
        }
        controller.stop(CONTAINER);

        assertTrue("WAIT mode: server log should contain WFLYTX0046 (recovery suspension initiated)",
            serverLogContainsSinceBaseline("WFLYTX0046"));
        assertTrue("WAIT mode: server log should contain WFLYTX0047 (recovery suspension completed)",
            serverLogContainsSinceBaseline("WFLYTX0047"));
    }

    @Test
    public void testRecoveryGracefulShutdownIgnoreMode() throws Exception {
        controller.start(CONTAINER);
        try (ModelControllerClient client = TestSuiteEnvironment.getModelControllerClient()) {
            writeAttribute(client, "transactions-recovery-graceful-shutdown", "ignore");
            ServerReload.reloadIfRequired(client);
            recordLogBaseline(client);
        }
        controller.stop(CONTAINER);

        assertTrue("IGNORE mode: server log should contain WFLYTX0046 (recovery suspension initiated)",
            serverLogContainsSinceBaseline("WFLYTX0046"));
    }

    // --- Utility methods ---

    private void recordLogBaseline(ModelControllerClient client) throws Exception {
        serverLogPath = LoggingUtil.getLogPath(client, "file-handler", "FILE");
        logBaseline = LoggingUtil.countLines(serverLogPath);
    }

    private boolean serverLogContainsSinceBaseline(String searchString) throws IOException {
        if (serverLogPath == null || !Files.exists(serverLogPath)) {
            return false;
        }
        try (Stream<String> lines = Files.lines(serverLogPath)) {
            return lines.skip(logBaseline).anyMatch(line -> line.contains(searchString));
        }
    }

    private ModelNode readAttribute(ModelControllerClient client, String name) throws IOException {
        ModelNode op = createReadAttributeOperation(TXN_SUBSYSTEM_ADDRESS, name);
        ModelNode response = client.execute(op);
        assertTrue("Read " + name + " failed: " + getFailureDescription(response),
            isSuccessfulOutcome(response));
        return response.get("result");
    }

    private ModelNode writeAttribute(ModelControllerClient client, String name, int value) throws IOException {
        ModelNode op = createWriteAttributeOperation(TXN_SUBSYSTEM_ADDRESS, name, value);
        return client.execute(op);
    }

    private ModelNode writeAttribute(ModelControllerClient client, String name, String value) throws IOException {
        ModelNode op = createWriteAttributeOperation(TXN_SUBSYSTEM_ADDRESS, name, value);
        return client.execute(op);
    }

    private void setSystemProperty(ModelControllerClient client, String name, String value) throws IOException {
        ModelNode address = createAddress("system-property", name);
        ModelNode op = createAddOperation(address);
        op.get("value").set(value);
        ModelNode result = client.execute(op);
        assertTrue("Failed to set system property " + name + ": " + getFailureDescription(result),
            isSuccessfulOutcome(result));
    }

    private void cleanMarkerDirectory() {
        try {
            if (Files.exists(MARKER_DIR_PATH)) {
                try (Stream<Path> files = Files.list(MARKER_DIR_PATH)) {
                    files.forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
                }
            }
        } catch (IOException ignored) {}
    }
}
