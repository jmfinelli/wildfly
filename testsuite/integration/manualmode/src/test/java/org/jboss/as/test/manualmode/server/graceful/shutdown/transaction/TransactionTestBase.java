package org.jboss.as.test.manualmode.server.graceful.shutdown.transaction;

import org.jboss.as.arquillian.container.ManagementClient;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.test.shared.TestSuiteEnvironment;
import org.jboss.dmr.ModelNode;
import org.junit.Assert;
import org.wildfly.test.api.Authentication;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ADD;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.CHILD_TYPE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OUTCOME;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.READ_ATTRIBUTE_OPERATION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.READ_CHILDREN_NAMES_OPERATION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.REMOVE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.RESULT;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SUCCESS;

public class TransactionTestBase {

    static final String CONTAINER = "graceful-shutdown-server";
    static final String DEPLOYMENT = "deployment";
    static final int recoveryBackoffPeriod = 1;
    static final int periodicRecoveryPeriod = 5;
    ModelControllerClient modelControllerClient;
    ManagementClient managementClient;

    static ManagementClient createManagementClient(final ModelControllerClient client) throws UnknownHostException {

        final int managementPort = TestSuiteEnvironment.getServerPort();
        final String managementAddress = TestSuiteEnvironment.formatPossibleIpv6Address(TestSuiteEnvironment.getServerAddress());

        return new ManagementClient(client, managementAddress, managementPort, "remote+http");
    }

    static ModelControllerClient createModelControllerClient(final String container) throws UnknownHostException {

        if (container == null) {
            throw new IllegalArgumentException("container cannot be null");
        }

        final int managementPort = TestSuiteEnvironment.getServerPort();
        final String managementAddress = TestSuiteEnvironment.formatPossibleIpv6Address(TestSuiteEnvironment.getServerAddress());

        return ModelControllerClient.Factory.create(InetAddress.getByName(managementAddress),
                managementPort,
                Authentication.getCallbackHandler());
    }

    //========================================
    //=========== utility methods ============
    //========================================

    String getState(ModelControllerClient modelControllerClient) throws IOException {

        final ModelNode readState = new ModelNode();
        readState.get(OP).set(READ_ATTRIBUTE_OPERATION);
        readState.get("name").set("suspend-state");

        ModelNode ret = modelControllerClient.execute(readState);

        Assert.assertEquals("The operation to read the state of the Server failed!", SUCCESS, ret.get(OUTCOME).asString());

        return ret.get(RESULT).asString();
    }

    void probe(ModelControllerClient modelControllerClient) throws IOException {

        PathAddress pathAddress = PathAddress
                .pathAddress("subsystem", "transactions")
                .append("log-store", "log-store");

        final ModelNode probe = new ModelNode();
        probe.get(OP).set("probe");
        probe.get(OP_ADDR).set(pathAddress.toModelNode());

        ModelNode ret = modelControllerClient.execute(probe);

        Assert.assertEquals("The operation to probe the log store failed!", SUCCESS, ret.get(OUTCOME).asString());
    }

    List<ModelNode> readTxnsFromLogStore(ModelControllerClient modelControllerClient) throws IOException {

        probe(modelControllerClient);

        PathAddress pathAddress = PathAddress
                .pathAddress("subsystem", "transactions")
                .append("log-store", "log-store");

        final ModelNode readTxns = new ModelNode();
        readTxns.get(OP).set(READ_CHILDREN_NAMES_OPERATION);
        readTxns.get(OP_ADDR).set(pathAddress.toModelNode());
        readTxns.get(CHILD_TYPE).set("transactions");

        ModelNode ret = modelControllerClient.execute(readTxns);

        Assert.assertEquals("The operation to read transactions from the log store failed!", SUCCESS, ret.get(OUTCOME).asString());

        return ret.get(RESULT).asList();
    }

    void deleteTxn(ModelControllerClient modelControllerClient, String txnId) throws IOException {

        PathAddress pathAddress = PathAddress
                .pathAddress("subsystem", "transactions")
                .append("log-store", "log-store")
                .append("transactions", txnId);

        final ModelNode deleteTxn = new ModelNode();
        deleteTxn.get(OP_ADDR).set(pathAddress.toModelNode());
        deleteTxn.get(OP).set("delete");

        ModelNode ret = modelControllerClient.execute(deleteTxn);

        Assert.assertEquals(String.format("The operation to delete the transaction with ID %s from the log store failed!", txnId), SUCCESS, ret.get(OUTCOME).asString());
    }

    void deleteAllTransactions(ModelControllerClient modelControllerClient) throws Exception {
        List<ModelNode> txnsList = readTxnsFromLogStore(modelControllerClient);

        for (ModelNode txn : txnsList) {
            deleteTxn(modelControllerClient, txn.asString());
        }
    }

    void shutdownServer(ModelControllerClient modelControllerClient, int seconds) throws IOException {

        final ModelNode operation = new ModelNode();
        operation.get(OP).set("shutdown");
        operation.get("suspend-timeout").set(seconds);

        ModelNode ret = modelControllerClient.execute(operation);

        Assert.assertEquals("The operation to shut down the server failed!", SUCCESS, ret.get(OUTCOME).asString());
    }

    static class SetupRecovery {

        private final int periodicRecoveryPeriod;
        private final int recoveryBackoffPeriod;

        SetupRecovery(int periodicRecoveryPeriod, int recoveryBackoffPeriod) {
            this.periodicRecoveryPeriod = periodicRecoveryPeriod;
            this.recoveryBackoffPeriod = recoveryBackoffPeriod;
        }

        void setUpRecovery(ModelControllerClient modelControllerClient, ManagementClient managementClient) throws IOException {

            PathAddress pathAddress = PathAddress.pathAddress("system-property", "RecoveryEnvironmentBean.periodicRecoveryPeriod");
            ModelNode addPeriodicRecoveryPeriod = new ModelNode();
            addPeriodicRecoveryPeriod.get(OP_ADDR).set(pathAddress.toModelNode());
            addPeriodicRecoveryPeriod.get(OP).set(ADD);
            addPeriodicRecoveryPeriod.get("value").set(periodicRecoveryPeriod);

            ModelNode ret = modelControllerClient.execute(addPeriodicRecoveryPeriod);

            Assert.assertEquals("Addition of system property RecoveryEnvironmentBean.periodicRecoveryPeriod failed!", SUCCESS, ret.get(OUTCOME).asString());

            pathAddress = PathAddress.pathAddress("system-property", "RecoveryEnvironmentBean.recoveryBackoffPeriod");
            ModelNode addRecoveryBackoffPeriod = new ModelNode();
            addRecoveryBackoffPeriod.get(OP_ADDR).set(pathAddress.toModelNode());
            addRecoveryBackoffPeriod.get(OP).set(ADD);
            addRecoveryBackoffPeriod.get("value").set(recoveryBackoffPeriod);

            ret = modelControllerClient.execute(addRecoveryBackoffPeriod);

            Assert.assertEquals("Addition of system property RecoveryEnvironmentBean.recoveryBackoffPeriod failed!", SUCCESS, ret.get(OUTCOME).asString());
        }

        void tearDownRecovery(ModelControllerClient modelControllerClient) throws IOException {

            PathAddress pathAddress = PathAddress.pathAddress("system-property", "RecoveryEnvironmentBean.periodicRecoveryPeriod");
            ModelNode removePeriodicRecoveryPeriod = new ModelNode();
            removePeriodicRecoveryPeriod.get(OP_ADDR).set(pathAddress.toModelNode());
            removePeriodicRecoveryPeriod.get(OP).set(REMOVE);

            ModelNode ret = modelControllerClient.execute(removePeriodicRecoveryPeriod);

            Assert.assertEquals("Removal of system property RecoveryEnvironmentBean.periodicRecoveryPeriod failed!", SUCCESS, ret.get(OUTCOME).asString());

            pathAddress = PathAddress.pathAddress("system-property", "RecoveryEnvironmentBean.recoveryBackoffPeriod");
            ModelNode removeRecoveryBackoffPeriod = new ModelNode();
            removeRecoveryBackoffPeriod.get(OP_ADDR).set(pathAddress.toModelNode());
            removeRecoveryBackoffPeriod.get(OP).set(REMOVE);

            ret = modelControllerClient.execute(removeRecoveryBackoffPeriod);

            Assert.assertEquals("Removal of system property RecoveryEnvironmentBean.recoveryBackoffPeriod failed!", SUCCESS, ret.get(OUTCOME).asString());
        }

    }

}
