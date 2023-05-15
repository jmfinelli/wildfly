package org.jboss.as.test.manualmode.server.graceful.shutdown.transaction.deployment;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;

@ApplicationPath(JaxRsActivator.ROOT)
public class JaxRsActivator extends Application {

    public static final String ROOT = "/rest";
}
