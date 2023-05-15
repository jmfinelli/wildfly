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
import org.jboss.as.test.integration.transactions.TestXAResource;
import org.jboss.as.test.integration.transactions.TransactionCheckerSingleton;
import org.jboss.as.test.integration.transactions.TransactionCheckerSingletonRemote;
import org.jboss.as.test.manualmode.server.graceful.shutdown.transaction.deployment.JaxRsActivator;
import org.jboss.as.test.manualmode.server.graceful.shutdown.transaction.deployment.SimpleTxn;
import org.jboss.as.test.shared.TimeoutUtil;
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

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;

@RunWith(Arquillian.class)
@RunAsClient
public class InDoubtTxnGracefulShutdownTestCase extends AbstractCliTestBase {

    private static final String CONTAINER = "graceful-shutdown-server";
    private static final String DEPLOYMENT = "deployment";
    private static final Logger log = Logger.getLogger(InDoubtTxnGracefulShutdownTestCase.class);

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
        initCLI(TimeoutUtil.adjust(20 * 1000));
        deployer.deploy(DEPLOYMENT);
    }

    @After
    public void stopContainer() throws Exception {
        deployer.undeploy(DEPLOYMENT);
        closeCLI();
        containerController.stop(CONTAINER);
    }

    @Test
    public void createSimpleTxn(@ArquillianResource @OperateOnDeployment(DEPLOYMENT) URL baseURL) throws URISyntaxException{

        URI simpleTxnUri = UriBuilder.fromUri(baseURL.toURI())
                .path(JaxRsActivator.ROOT)
                .path(SimpleTxn.SIMPLE_TXN_PATH)
                .build();

        Response response = client
                .target(simpleTxnUri)
                .request()
                .post(null);

        Assert.assertEquals("The HTTP request to create a new transaction succeed...but it should have failed!",
                500, response.getStatus());

        suspendServer(10);
    }

    private void suspendServer(int seconds) {
        String suspendCommand = String.format(":suspend(suspend-timeout=%d)", seconds);
        cli.sendLine(suspendCommand);
    }

}
