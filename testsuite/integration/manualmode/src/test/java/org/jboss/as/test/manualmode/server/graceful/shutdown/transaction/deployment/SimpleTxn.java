package org.jboss.as.test.manualmode.server.graceful.shutdown.transaction.deployment;

import jakarta.annotation.Resource;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.UserTransaction;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import org.jboss.as.test.integration.transactions.TestXAResource;

@Path(SimpleTxn.SIMPLE_TXN_PATH)
public class SimpleTxn {

    public static final String SIMPLE_TXN_PATH = "/txn";

    @Resource(lookup = "java:comp/UserTransaction")
    UserTransaction userTransaction;

    @Resource(lookup = "java:/TransactionManager")
    TransactionManager transactionManager;

    @POST
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

    @GET
    public String get() {
        return "Hello";
    }

}
