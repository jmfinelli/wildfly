package org.jboss.as.test.manualmode.server.graceful.shutdown.transaction;

import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import org.jboss.arquillian.container.test.api.ContainerController;
import org.jboss.arquillian.container.test.api.Deployer;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.OperateOnDeployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.container.test.api.TargetsContainer;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.as.test.integration.management.base.AbstractCliTestBase;
import org.jboss.as.test.integration.management.util.CLIOpResult;
import org.jboss.as.test.integration.transactions.TestXAResource;
import org.jboss.as.test.integration.transactions.TransactionCheckerSingleton;
import org.jboss.as.test.integration.transactions.TransactionCheckerSingletonRemote;
import org.jboss.as.test.manualmode.server.graceful.shutdown.transaction.deployment.JaxRsActivator;
import org.jboss.as.test.manualmode.server.graceful.shutdown.transaction.deployment.SimpleTxn;
import org.jboss.as.test.shared.TimeoutUtil;
import org.jboss.dmr.ModelNode;
import org.jboss.logging.Logger;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@RunWith(Arquillian.class)
@RunAsClient
public class InDoubtTxnGracefulShutdownTestCase extends AbstractCliTestBase {

    private static final String CONTAINER = "graceful-shutdown-server";
    private static final String DEPLOYMENT = "deployment";
    private static final Logger log = Logger.getLogger(InDoubtTxnGracefulShutdownTestCase.class);
    private static final int recoveryBackoffPeriod = 1;
    private static final int periodicRecoveryPeriod = 5;

    @ArquillianResource
    private static ContainerController containerController;

    @ArquillianResource
    Deployer deployer;

    private static Client client;

    @BeforeClass
    public static void setUpClient() {
        client = ClientBuilder.newClient();
    }

    @AfterClass
    public static void close() {
        client.close();
    }

    @TargetsContainer(CONTAINER)
    @Deployment(name = DEPLOYMENT, managed = false, testable = false)
    public static WebArchive createDeployment() {
        return ShrinkWrap.create(WebArchive.class, DEPLOYMENT + ".war")
                .addClasses(JaxRsActivator.class, SimpleTxn.class,
                        TestXAResource.class, TransactionCheckerSingleton.class, TransactionCheckerSingletonRemote.class)
                .addAsWebInfResource(new StringAsset("<beans bean-discovery-mode=\"all\"></beans>"), "beans.xml");
    }

    @Before
    public void startContainer() throws Exception {
        containerController.start(CONTAINER);
        initCLI(TimeoutUtil.adjust(10 * 1000));
        deployer.deploy(DEPLOYMENT);

        setUpRecovery(periodicRecoveryPeriod, recoveryBackoffPeriod);

        // Restart container
        containerController.stop(CONTAINER);
        containerController.start(CONTAINER);
    }

    @After
    public void stopContainer() throws Exception {
        closeCLI();
    }

    @Test
    public void testSimppleHeuristicTxn(@ArquillianResource @OperateOnDeployment(DEPLOYMENT) URL baseURL) throws Exception {

        URI simpleTxnBaseUri = UriBuilder.fromUri(baseURL.toURI())
                .path(JaxRsActivator.ROOT)
                .path(SimpleTxn.TXN_GENERATOR_PATH)
                .build();

        Response response = client
                .target(simpleTxnBaseUri)
                .path(SimpleTxn.SIMPLE_HEURISTIC_PATH)
                .request()
                .post(null);

        Assert.assertEquals("The HTTP request succeed but it should have failed!",
                500, response.getStatus());

        // Suspend WildFly with infinite timeout
        shutdownServer(-1);

        short counter = 0;
        do {
            Thread.sleep(200);
            counter++;
        } while (!getState().equals("SUSPENDING") && counter < 10);

        // The Transactions subsystem should stop the suspension
        Assert.assertEquals("Server is not SUSPENDING!", "SUSPENDING", getState());

        // Wait some time to allow WildFly's notification system to print out a WARN
        // warning that there is a pending txn in the log store
        Thread.sleep(periodicRecoveryPeriod * 1000);

        deleteAllTransactions();

        // Let's undeploy the test application now that we are still in time
        deployer.undeploy(DEPLOYMENT);

        counter = 0;
        do {
            Thread.sleep(500);
            counter++;
        } while (checkServerIsAlive(TimeoutUtil.adjust(10 * 1000)) && counter < 40);

        // Let's give WildFly a bit of time to shut down
        Thread.sleep(1000);

        // WildFly will shut down here. Let's check with the Client
        Assert.assertFalse("Server should have been shut down!", checkServerIsAlive(TimeoutUtil.adjust(10 * 1000)));
    }

    private void setUpRecovery(int periodicRecoveryPeriod, int recoveryBackoffPeriod) throws IOException {
        String setPeriodicRecoveryPeriodCommand = String.format(
                "/system-property=RecoveryEnvironmentBean.periodicRecoveryPeriod:add(value=%d)",
                periodicRecoveryPeriod);
        String setBackOffPeriod = String.format(
                "/system-property=RecoveryEnvironmentBean.recoveryBackoffPeriod:add(value=%d)",
                recoveryBackoffPeriod);

        cli.sendLine(setPeriodicRecoveryPeriodCommand);
        cli.sendLine(setBackOffPeriod);

        cli.sendLine("reload");
    }

    private void shutdownServer(int seconds) {
        String suspendCommand = String.format(":shutdown(suspend-timeout=%d)", seconds);
        cli.sendLine(suspendCommand);
    }

    private String getState() throws IOException {
        String isSuspendingCommand = ":read-attribute(name=suspend-state)";
        cli.sendLine(isSuspendingCommand);

        CLIOpResult result = cli.readAllAsOpResult();
        if (result.isIsOutcomeSuccess()) {
            String state = result.getResult().toString();
            return state;
        }

        throw new RuntimeException("Something went wrong reading WildFly's suspend-state!");
    }

    private List<String> checkForTxns() throws IOException {
        String probe = "/subsystem=transactions/log-store=log-store:probe()";
        String readTransactions = "/subsystem=transactions/log-store=log-store:read-children-names(child-type=transactions)";

        List<String> returnList = new ArrayList<>();
        cli.sendLine(probe);
        cli.sendLine(readTransactions);
        CLIOpResult result = cli.readAllAsOpResult();
        if (result.isIsOutcomeSuccess()) {
            ModelNode resultNode = result.getResponseNode().get("result");
            returnList.addAll(resultNode
                    .asListOrEmpty()
                    .stream()
                    .map(x -> x.asString())
                    .collect(Collectors.toList()));
        }

        return returnList;
    }

    private void deleteAllTransactions() throws Exception {
        String templateToDeleteTransactions = "/subsystem=transactions/log-store=log-store/transactions=%s:delete()";

        for (String transaction : checkForTxns()) {
            cli.sendLine(String.format(templateToDeleteTransactions, transaction.replace(":", "\\:")));
        }
    }

}
