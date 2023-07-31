package org.jboss.as.test.manualmode.server.graceful.shutdown.transaction.deployments.basic;

import jakarta.annotation.Resource;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.UserTransaction;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import org.jboss.as.test.integration.transactions.TestXAResource;

@Path(SimpleTxn.TXN_GENERATOR_PATH)
public class SimpleTxn {

    public static final String TXN_GENERATOR_PATH = "/txn-generator";
    public static final String SIMPLE_SUCCESSFUL_PATH = "/atomic-action-successful-commit";
    public static final String SIMPLE_HEURISTIC_PATH = "/atomic-action-heuristic-commit";

    @Resource(lookup = "java:comp/UserTransaction")
    UserTransaction userTransaction;

    @Resource(lookup = "java:/TransactionManager")
    TransactionManager transactionManager;

    @POST
    @Path(SIMPLE_SUCCESSFUL_PATH)
    public Response successfulCommit() {
        try {
            userTransaction.begin();

            transactionManager.getTransaction().enlistResource(
                    new TestXAResource(TestXAResource.TestAction.NONE));
            transactionManager.getTransaction().enlistResource(
                    new TestXAResource(TestXAResource.TestAction.NONE));

            userTransaction.commit();

            return Response.ok().build();
        } catch (Exception exception) {
            return Response.serverError().build();
        }
    }

    @POST
    @Path(SIMPLE_HEURISTIC_PATH)
    public Response failCommit() {
        try {
            userTransaction.begin();

            transactionManager.getTransaction().enlistResource(
                    new TestXAResource(TestXAResource.TestAction.COMMIT_THROW_UNKNOWN_XA_EXCEPTION));
            transactionManager.getTransaction().enlistResource(
                    new TestXAResource(TestXAResource.TestAction.NONE));

            userTransaction.commit();

            return Response.ok().build();
        } catch (Exception exception) {
            return Response.serverError().build();
        }
    }
}
