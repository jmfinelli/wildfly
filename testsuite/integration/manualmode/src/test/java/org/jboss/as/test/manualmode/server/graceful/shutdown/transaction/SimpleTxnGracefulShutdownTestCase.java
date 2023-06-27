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
import org.jboss.as.test.integration.transactions.TestXAResource;
import org.jboss.as.test.integration.transactions.TransactionCheckerSingleton;
import org.jboss.as.test.integration.transactions.TransactionCheckerSingletonRemote;
import org.jboss.as.test.manualmode.server.graceful.shutdown.transaction.deployments.JaxRsActivator;
import org.jboss.as.test.manualmode.server.graceful.shutdown.transaction.deployments.SimpleTxn;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;

@RunWith(Arquillian.class)
@RunAsClient
public class SimpleTxnGracefulShutdownTestCase extends TransactionTestBase {

    private static SetupRecovery setUpRecovery = new SetupRecovery(periodicRecoveryPeriod, recoveryBackoffPeriod);

    @ArquillianResource
    private static ContainerController containerController;

    @ArquillianResource
    private Deployer deployer;

    private static Client client;

    @BeforeClass
    public static void setUpClient() {
        client = ClientBuilder.newClient();
    }

    @AfterClass
    public static void close() {
        client.close();
    }

    @Before
    public void startServer() throws IOException {
        containerController.start(CONTAINER);

        // Let's configure the server
        this.modelControllerClient = createModelControllerClient(CONTAINER);
        this.managementClient = createManagementClient(modelControllerClient);
        setUpRecovery.setUpRecovery(modelControllerClient, managementClient);

        containerController.stop(CONTAINER);
        containerController.start(CONTAINER);

        deployer.deploy(DEPLOYMENT);
    }

    @TargetsContainer(CONTAINER)
    @Deployment(name = DEPLOYMENT, managed = false, testable = false)
    public static WebArchive createDeployment() {
        return ShrinkWrap.create(WebArchive.class, DEPLOYMENT + ".war")
                .addClasses(JaxRsActivator.class, SimpleTxn.class,
                        TestXAResource.class, TransactionCheckerSingleton.class, TransactionCheckerSingletonRemote.class)
                .addAsWebInfResource(new StringAsset("<beans bean-discovery-mode=\"all\"></beans>"), "beans.xml");
    }

    @Test
    public void testSimppleHeuristicTxn(@ArquillianResource @OperateOnDeployment(DEPLOYMENT) URL baseURL) throws Exception {

        createSimpleTxn(baseURL, client);

        // Shut down WildFly with infinite timeout
        shutdownServer(modelControllerClient, -1);

        short counter = 0;
        short attempts = 10;
        do {
            Thread.sleep(200);
            counter++;
        } while (!getState(modelControllerClient).equals("SUSPENDING") && counter < attempts);

        // The Transactions subsystem should delay the suspension
        if (!(counter < attempts)) {
            Assert.fail("Server is not SUSPENDING!");
        }

        // Wait some time to allow WildFly's notification system to print out a WARN
        // warning that there is a pending txn in the log store
        Thread.sleep(2 * periodicRecoveryPeriod * 1000);

        deleteAllTransactions(modelControllerClient);

        // At this point, we are just waiting that the server goes down,
        // so we can remove the system properties defined at boot time
        setUpRecovery.tearDownRecovery(modelControllerClient);

        // Let's undeploy the test application now that we are still in time
        deployer.undeploy(DEPLOYMENT);

        counter = 0;
        attempts = 20;
        do {
            Thread.sleep(500);
            counter++;
        } while (managementClient.isServerInRunningState() && counter < attempts);

        if (!(counter < attempts)) {
            Assert.fail("The server didn't shut down!");
        }

        try {
            // This is a workaround to avoid failing because Arquillian
            // does not handle very well when the container was already
            // shut down
            containerController.stop(CONTAINER);
        } catch (Exception ex) {
            // The server has already shut down
        }
    }

    private void createSimpleTxn(URL baseURL, Client client) throws URISyntaxException {

        URI simpleTxnBaseUri = UriBuilder.fromUri(baseURL.toURI())
                .path(JaxRsActivator.ROOT)
                .path(SimpleTxn.TXN_GENERATOR_PATH)
                .build();

        Response response = client
                .target(simpleTxnBaseUri)
                .path(SimpleTxn.SIMPLE_HEURISTIC_PATH)
                .request()
                .post(null);

        Assert.assertEquals("The HTTP request succeed but it should have failed!", 500, response.getStatus());
    }
}
