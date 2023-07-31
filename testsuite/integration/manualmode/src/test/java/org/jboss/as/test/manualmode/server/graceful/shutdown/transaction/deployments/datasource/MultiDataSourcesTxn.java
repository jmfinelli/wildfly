package org.jboss.as.test.manualmode.server.graceful.shutdown.transaction.deployments.datasource;

import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

@Path(MultiDataSourcesTxn.TXN_GENERATOR_PATH)
public class MultiDataSourcesTxn {

    public static final String TXN_GENERATOR_PATH = "/datasource-txn-generator";
    public static final String TRANSACTION_CREATION_PATH = "/transaction-creation";

    @Inject
    private Bean transactionalBean;

    @POST
    @Path(MultiDataSourcesTxn.TRANSACTION_CREATION_PATH)
    public Response failCommit() {
        try {
            transactionalBean.createEntriesInDBs();
            return Response.ok().build();
        } catch (Exception ex) {
            return Response.serverError().build();
        }
    }

}
