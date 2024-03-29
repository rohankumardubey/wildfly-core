/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012, Red Hat, Inc., and individual contributors
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
package org.jboss.as.controller.audit;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.logging.ControllerLogger;
import org.jboss.dmr.ModelNode;

/**
 *  All methods on this class should be called with {@link ManagedAuditLoggerImpl}'s lock taken.
 *
 * @author <a href="kabir.khan@jboss.com">Kabir Khan</a>
 */
abstract class AuditLogHandler {

    /** Maximum number of consecutive logging failures before we stop logging */
    private volatile int maxFailureCount = 10;

    /** The number of consecutive failures writing to the log */
    private int failureCount;


    protected final String name;
    private volatile String formatterName;
    private final Set<PathAddress> references = new HashSet<PathAddress>();
    private AuditLogItemFormatter formatter;

    AuditLogHandler(String name, String formatterName, int maxFailureCount){
        this.name = name;
        this.formatterName = formatterName;
        this.maxFailureCount = maxFailureCount;
    }

    String getName() {
        return name;
    }

    void setFormatter(AuditLogItemFormatter formatter) {
        this.formatter = formatter;
    }

    String getFormatterName() {
        return formatterName;
    }

    public void setMaxFailureCount(int count) {
        this.maxFailureCount = count;
    }

    public void setFormatterName(String formatterName) {
        this.formatterName = formatterName;
    }

    void writeLogItem(AuditLogItem item) {
        FailureCountHandler fch = getFailureCountHandler();
        try {
            initialize();
            String formattedItem = item.format(formatter);
            writeLogItem(formattedItem);
            fch.success();
        } catch (Throwable t) {
            fch.failure(t);
        }
    }

    void recycle() {
        this.failureCount = 0;
        stop();
    }

    boolean isActive() {
        return !hasTooManyFailures();
    }

    boolean isDisabledDueToFailures() {
        return hasTooManyFailures();
    }

    boolean hasTooManyFailures() {
        return maxFailureCount > 0 && failureCount >= maxFailureCount;
    }

    void addReference(PathAddress address){
        references.add(address);
    }

    void removeReference(PathAddress address){
        references.remove(address);
        if (references.isEmpty()){
            stop();
        }
    }

    Set<PathAddress> getReferences(){
        return references;
    }

    int getFailureCount() {
        return failureCount;
    }

    FailureCountHandler getFailureCountHandler() {
        return new StandardFailureCountHandler();
    }

    abstract boolean isDifferent(AuditLogHandler other);
    abstract void initialize();
    abstract void stop();
    abstract void writeLogItem(String formattedItem) throws IOException;

    List<ModelNode> listLastEntries() {
        return Collections.emptyList();
    }

    interface FailureCountHandler {
        void success();
        void failure(Throwable t);
    }

    class StandardFailureCountHandler implements FailureCountHandler {
        @Override
        public void success() {
            failureCount = 0;
        }

        @Override
        public void failure(Throwable t) {
            failureCount++;
            ControllerLogger.MGMT_OP_LOGGER.logHandlerWriteFailed(t, name);
            if (hasTooManyFailures()) {
                ControllerLogger.MGMT_OP_LOGGER.disablingLogHandlerDueToFailures(failureCount, name);
            }
        }
    }

    class ReconnectFailureCountHandler implements FailureCountHandler {
        @Override
        public void success() {
            failureCount = 0;
        }

        @Override
        public void failure(Throwable t) {
            ControllerLogger.MGMT_OP_LOGGER.reconnectToSyslogFailed(name, t);
        }
    }
}
