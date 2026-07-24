/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.as.test.manualmode.transaction;

import jakarta.ejb.Stateless;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.transaction.TransactionSynchronizationRegistry;
import javax.naming.InitialContext;

@Stateless
public class TransactionHelper {

    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public void doWork() throws Exception {
        InitialContext ctx = new InitialContext();
        TransactionSynchronizationRegistry tsr =
            (TransactionSynchronizationRegistry) ctx.lookup("java:comp/TransactionSynchronizationRegistry");
        int status = tsr.getTransactionStatus();
        if (status != jakarta.transaction.Status.STATUS_ACTIVE) {
            throw new IllegalStateException("Expected STATUS_ACTIVE but got " + status);
        }
    }
}
