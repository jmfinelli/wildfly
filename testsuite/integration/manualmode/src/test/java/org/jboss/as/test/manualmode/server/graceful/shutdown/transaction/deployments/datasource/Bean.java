package org.jboss.as.test.manualmode.server.graceful.shutdown.transaction.deployments.datasource;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;

public class Bean {

    @PersistenceContext(unitName = "TestXADB1")
    EntityManager firstEm;

    @PersistenceContext(unitName = "TestXADB2")
    EntityManager secondEm;

    @Transactional
    public void createEntriesInDBs() {

        FirstEntity firstEntity = new FirstEntity();
        firstEm.persist(firstEntity);

        Long firstEntityId = firstEntity.getId();
        SecondEntity secondEntity = new SecondEntity();
        secondEntity.setFirstEntityId(firstEntityId);
        secondEm.persist(secondEntity);
    }
}
