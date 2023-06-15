/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2016, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.txn.suspend;

import com.arjuna.ats.arjuna.common.RecoveryEnvironmentBean;
import com.arjuna.ats.jbossatx.jta.RecoveryManagerService;
import com.arjuna.ats.jbossatx.jta.TransactionManagerService;
import com.arjuna.ats.jta.transaction.Transaction;
import com.arjuna.common.internal.util.propertyservice.BeanPopulator;
import org.jboss.as.controller.ControlledProcessState;
import org.jboss.as.server.suspend.ServerActivity;
import org.jboss.as.server.suspend.ServerActivityCallback;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.concurrent.Callable;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.jboss.as.txn.logging.TransactionLogger.ROOT_LOGGER;

/**
 * Listens for notifications from a {@code SuspendController} and a {@code ProcessStateNotifier} and reacts
 * to them by {@link RecoveryManagerService#suspend() suspending} or {@link RecoveryManagerService#resume() resuming}
 * the {@link RecoveryManagerService}.
 *
 * @author <a href="mailto:gytis@redhat.com">Gytis Trikleris</a>
 */
public class RecoverySuspendController implements ServerActivity, PropertyChangeListener {
    private final RecoveryEnvironmentBean recoveryEnvironmentBean = BeanPopulator.getDefaultInstance(RecoveryEnvironmentBean.class);
    private final RecoveryManagerService recoveryManagerService;
    private final TransactionManagerService transactionManagerService;
    private volatile boolean suspended = false;
    private volatile boolean running;

    public RecoverySuspendController(RecoveryManagerService recoveryManagerService, TransactionManagerService transactionManagerService) {
        this.recoveryManagerService = recoveryManagerService;
        this.transactionManagerService = transactionManagerService;
    }

    /**
     * Do nothing.
     */
    @Override
    public Callable<Void> preSuspend(ServerActivityCallback listener) {
        return () -> {
            transactionManagerService.disableTransactionCreation();
            return listener.done();
        };
    }

    @Override
    public Callable<Void> suspended(ServerActivityCallback serverActivityCallback) {

        return () -> {

            int numberOfTransactions;
            int numberOfLeftOverTransactions;

            do {
                numberOfLeftOverTransactions = recoveryManagerService.leftOverTransactions();
                numberOfTransactions = transactionManagerService.getTransactions().size();

                // Here, in-flight transactions are processed to work out the maximum timeout
                // needed to make sure that enough time is elapsed before going ahead.
                if (numberOfTransactions > 0) {
                    // As numberOfTransactions is > 0, isPresent() can be avoided
                    long timeout = SECONDS.toMillis(
                            Integer.max(
                                    transactionManagerService.getTransactions().values().stream().mapToInt(Transaction::getTimeout).max().getAsInt(),
                                    recoveryEnvironmentBean.getRecoveryBackoffPeriod()));

                    ROOT_LOGGER.inFlightTransactionToCompleteBeforeSuspension(numberOfTransactions, timeout);
                    Thread.sleep(timeout);
                }

                if (numberOfLeftOverTransactions > 0) {
                    long delay = SECONDS.toMillis(recoveryEnvironmentBean.getRecoveryBackoffPeriod());
                    ROOT_LOGGER.inDoubtTransactionToCompleteBeforeSuspension(numberOfLeftOverTransactions, delay);
                    Thread.sleep(delay);
                }

            } while (numberOfTransactions != 0 || numberOfLeftOverTransactions != 0);

            recoveryManagerService.suspend();
            suspended = true;
            return serverActivityCallback.done();
        };
    }

    /**
     * {@link RecoveryManagerService#resume() Resumes} the {@link RecoveryManagerService} if the current
     * process state {@link ControlledProcessState.State#isRunning() is running}. Otherwise records that
     * the service can be resumed once a {@link #propertyChange(PropertyChangeEvent) notification is received} that
     * the process state is running.
     */
    @Override
    public Callable<Void> resume() {
        return () -> {
            boolean doResume;

            suspended = false;
            doResume = running;

            if (doResume) {
                resumeRecovery();
            }

            transactionManagerService.enableTransactionCreation();
            return null;
        };
    }

    /**
     * Receives notifications from a {@code ProcessStateNotifier} to detect when the process has reached a
     * {@link ControlledProcessState.State#isRunning()}  running state}, reacting to them by
     * {@link RecoveryManagerService#resume() resuming} the {@link RecoveryManagerService} if we haven't been
     * {@link #preSuspend(ServerActivityCallback) suspended}.
     */
    @Override
    public void propertyChange(PropertyChangeEvent evt) {
        boolean doResume;
        ControlledProcessState.State newState = (ControlledProcessState.State) evt.getNewValue();
        running = newState.isRunning();
        doResume = running && !suspended;
        if (doResume) {
            resumeRecovery();
        }
    }

    private void resumeRecovery() {
        recoveryManagerService.resume();
    }
}
