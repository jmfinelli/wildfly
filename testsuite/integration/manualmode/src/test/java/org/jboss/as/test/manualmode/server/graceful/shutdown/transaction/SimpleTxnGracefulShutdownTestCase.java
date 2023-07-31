package org.jboss.as.test.manualmode.server.graceful.shutdown.transaction;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.OperateOnDeployment;
import org.jboss.arquillian.container.test.api.TargetsContainer;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.as.test.integration.transactions.TestXAResource;
import org.jboss.as.test.integration.transactions.TransactionCheckerSingleton;
import org.jboss.as.test.integration.transactions.TransactionCheckerSingletonRemote;
import org.jboss.as.test.manualmode.server.graceful.shutdown.transaction.deployments.JaxRsActivator;
import org.jboss.as.test.manualmode.server.graceful.shutdown.transaction.deployments.basic.SimpleTxn;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.Test;

import java.net.URL;

public class SimpleTxnGracefulShutdownTestCase extends TransactionTestBase {
    private static final String SIMPLE_TXN_DEPLOYMENT = "simpleTxnDeployment";

    @Override
    String getDeploymentName() {
        return SIMPLE_TXN_DEPLOYMENT;
    }

    @TargetsContainer(CONTAINER)
    @Deployment(name = SIMPLE_TXN_DEPLOYMENT, managed = false, testable = false)
    public static WebArchive createDeployment() {
        return ShrinkWrap.create(WebArchive.class, SIMPLE_TXN_DEPLOYMENT + ".war")
                .addClasses(JaxRsActivator.class, SimpleTxn.class,
                        TestXAResource.class, TransactionCheckerSingleton.class, TransactionCheckerSingletonRemote.class)
                .addAsWebInfResource(new StringAsset("<beans bean-discovery-mode=\"all\"></beans>"), "beans.xml");
    }

    @Test
    public void testSimpleSuccessfulTxn(@ArquillianResource @OperateOnDeployment(SIMPLE_TXN_DEPLOYMENT) URL baseURL) throws Exception {
        super.successfulTransactionCreationBase(baseURL, JaxRsActivator.ROOT,
                SimpleTxn.TXN_GENERATOR_PATH,
                SimpleTxn.SIMPLE_SUCCESSFUL_PATH,
                client, 200);
    }

    @Test
    public void testSimpleHeuristicTxn(@ArquillianResource @OperateOnDeployment(SIMPLE_TXN_DEPLOYMENT) URL baseURL) throws Exception {
        super.faultyTransactionCreationBase(baseURL, JaxRsActivator.ROOT,
                SimpleTxn.TXN_GENERATOR_PATH,
                SimpleTxn.SIMPLE_HEURISTIC_PATH,
                client, true, 500);
    }

    @Test
    public void retrySimpleHeuristicTxn(@ArquillianResource @OperateOnDeployment(SIMPLE_TXN_DEPLOYMENT) URL baseURL) throws Exception {
        super.faultyTransactionCreationBase(baseURL, JaxRsActivator.ROOT,
                SimpleTxn.TXN_GENERATOR_PATH,
                SimpleTxn.SIMPLE_RETRY_PATH,
                client, true, 500);
    }
}
