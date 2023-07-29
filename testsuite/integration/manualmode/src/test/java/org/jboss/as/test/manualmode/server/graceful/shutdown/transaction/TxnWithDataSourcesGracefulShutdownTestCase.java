package org.jboss.as.test.manualmode.server.graceful.shutdown.transaction;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.OperateOnDeployment;
import org.jboss.arquillian.container.test.api.TargetsContainer;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.test.manualmode.server.graceful.shutdown.transaction.deployments.JaxRsActivator;
import org.jboss.as.test.manualmode.server.graceful.shutdown.transaction.deployments.datasource.Bean;
import org.jboss.as.test.manualmode.server.graceful.shutdown.transaction.deployments.datasource.FirstEntity;
import org.jboss.as.test.manualmode.server.graceful.shutdown.transaction.deployments.datasource.MultiDataSourcesTxn;
import org.jboss.as.test.manualmode.server.graceful.shutdown.transaction.deployments.datasource.SecondEntity;
import org.jboss.byteman.agent.submit.Submit;
import org.jboss.dmr.ModelNode;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.Assert;
import org.junit.Test;

import java.net.URL;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ADD;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.COMPOSITE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OUTCOME;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.REMOVE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.STEPS;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SUCCESS;

public class TxnWithDataSourcesGracefulShutdownTestCase extends TransactionTestBase {

    private static String firstDataSourceName = "TestXADB1";
    private static String secondDataSourceName = "TestXADB2";
    private static final String TXN_WITH_DATASOURCE_DEPLOYMENT = "TxnWithDatasourceDeployment";
    private static CreateXADataSources createXADataSources = new CreateXADataSources();

    @Override
    void deploy() {
        deployer.deploy(TXN_WITH_DATASOURCE_DEPLOYMENT);
    }

    @Override
    void customServerConfiguration() throws Exception {
        super.customServerConfiguration();

        // Let's configure the server for this specific test
        createXADataSources.setupXADataSources(modelControllerClient, firstDataSourceName);
        createXADataSources.setupXADataSources(modelControllerClient, secondDataSourceName);
    }

    @Override
    void customServerTearDown() throws Exception {
        super.customServerTearDown();

        removeRules();

        // Rollback the Server's configuration
        createXADataSources.tearDownXADataSources(modelControllerClient, firstDataSourceName);
        createXADataSources.tearDownXADataSources(modelControllerClient, secondDataSourceName);
    }

    @TargetsContainer(CONTAINER)
    @Deployment(name = TXN_WITH_DATASOURCE_DEPLOYMENT, managed = false, testable = false)
    public static WebArchive createDeployment() {
        WebArchive war = ShrinkWrap.create(WebArchive.class, TXN_WITH_DATASOURCE_DEPLOYMENT + ".war")
                .addClasses(Bean.class, FirstEntity.class, SecondEntity.class, JaxRsActivator.class, MultiDataSourcesTxn.class)
                .addAsWebInfResource(new StringAsset("<beans bean-discovery-mode=\"all\"></beans>"), "beans.xml")
                .addAsResource("persistence/persistence.xml", "META-INF/persistence.xml");
        return war;
    }

    @Test
    public void testHeuristicTxnWithDataSources(@ArquillianResource @OperateOnDeployment(TXN_WITH_DATASOURCE_DEPLOYMENT) URL baseURL) throws Exception {

        // Add byteman rules to the server
        deployRules();

        super.heuristicTransactionCreationBase(baseURL, JaxRsActivator.ROOT,
                MultiDataSourcesTxn.TXN_GENERATOR_PATH,
                MultiDataSourcesTxn.SIMPLE_HEURISTIC_PATH,
                client, 500,
                TXN_WITH_DATASOURCE_DEPLOYMENT);
    }

    private static class CreateXADataSources {

        public void setupXADataSources(ModelControllerClient modelControllerClient, String dataSourceName) throws Exception {

            final String XADataSourceJndiName = String.format("java:jboss/datasources/%s", dataSourceName);
            // Let's start a batch of operations
            ModelNode batch = new ModelNode();
            batch.get(OP).set(COMPOSITE);
            batch.get(OP_ADDR).setEmptyList();

            PathAddress pathAddress = PathAddress
                    .pathAddress("subsystem", "datasources")
                    .append("xa-data-source", dataSourceName);
            ModelNode addXAResource = new ModelNode();
            addXAResource.get(OP_ADDR).set(pathAddress.toModelNode());
            addXAResource.get(OP).set(ADD);

            Properties datasourceProperties = new Properties();
            datasourceProperties.put("jndi-name", XADataSourceJndiName);
            datasourceProperties.put("driver-name", "h2");
            datasourceProperties.put("user-name", "sa");
            datasourceProperties.put("password", "sa");

            for(Map.Entry<Object, Object> property : datasourceProperties.entrySet()) {
                addXAResource.get(property.getKey().toString()).set(property.getValue().toString());
            }

            batch.get(STEPS).add(addXAResource);

            Properties xaDataSourceProperties = new Properties();
            xaDataSourceProperties.put("url", "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=TRUE;MODE=REGULAR");

            for(Map.Entry<Object, Object> property : xaDataSourceProperties.entrySet()) {
                final ModelNode propertyAddress = pathAddress.toModelNode().clone();
                propertyAddress.add("xa-datasource-properties", property.getKey().toString());
                propertyAddress.protect();

                final ModelNode addXAProperty = new ModelNode();
                addXAProperty.get(OP).set("add");
                addXAProperty.get(OP_ADDR).set(propertyAddress);
                addXAProperty.get("value").set(property.getValue().toString());

                batch.get(STEPS).add(addXAProperty);
            }

            // Let's execute the batch of operations
            ModelNode ret = modelControllerClient.execute(batch);

            Assert.assertEquals("Execution of the batch operation failed!", SUCCESS, ret.get(OUTCOME).asString());
        }

        public void tearDownXADataSources(ModelControllerClient modelControllerClient, String dataSourceName) throws Exception {

            PathAddress pathAddress = PathAddress
                    .pathAddress("subsystem", "datasources")
                    .append("xa-data-source", dataSourceName);
            ModelNode removeXAResource = new ModelNode();
            removeXAResource.get(OP_ADDR).set(pathAddress.toModelNode());
            removeXAResource.get(OP).set(REMOVE);

            ModelNode ret = modelControllerClient.execute(removeXAResource);

            Assert.assertEquals(String.format("Removal of Data Source %s failed!", dataSourceName), SUCCESS, ret.get(OUTCOME).asString());
        }
    }

    //========================================
    //======= Byteman utility methods ========
    //========================================

    private final Submit bytemanSubmit = new Submit(
            System.getProperty("byteman.server.ipaddress", Submit.DEFAULT_ADDRESS),
            Integer.getInteger("byteman.server.port", Submit.DEFAULT_PORT));

    private void deployRules() throws Exception {
        bytemanSubmit.addRulesFromResources(Collections.singletonList(
                TxnWithDataSourcesGracefulShutdownTestCase.class.getClassLoader().getResourceAsStream("byteman/TxnWithDataSourcesGracefulShutdownTestCase.btm")));
    }

    private void removeRules() {
        try {
            bytemanSubmit.deleteAllRules();
        } catch (Exception ex) {
        }
    }

}
