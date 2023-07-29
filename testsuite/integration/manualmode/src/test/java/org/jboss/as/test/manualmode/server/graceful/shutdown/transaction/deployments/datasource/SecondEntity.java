package org.jboss.as.test.manualmode.server.graceful.shutdown.transaction.deployments.datasource;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;

@Entity
public class SecondEntity {

    private Long id;
    private Long FirstEntityId;

    @Id
    @GeneratedValue
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    @Column(nullable = false)
    public Long getFirstEntityId() {
        return FirstEntityId;
    }

    public void setFirstEntityId(Long firstEntityId) {
        FirstEntityId = firstEntityId;
    }
}
