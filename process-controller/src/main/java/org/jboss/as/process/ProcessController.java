/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2010, Red Hat, Inc., and individual contributors
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

package org.jboss.as.process;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import org.jboss.as.process.logging.ProcessLogger;
import org.jboss.as.process.protocol.Connection;
import org.jboss.as.process.protocol.ProtocolServer;
import org.jboss.as.process.protocol.StreamUtils;

/**
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
public final class ProcessController {

    /**
     * Main lock - anything which opens a file descriptor or spawns a process must
     * hold this lock for the duration of the operation.
     */
    private final Object lock = new Object();

    private final ProtocolServer server;
    // Synchronized map so we can safely check its size without holding the monitor for field 'lock' */
    private final Map<String, ManagedProcess> processes = Collections.synchronizedMap(new HashMap<String, ManagedProcess>());
    private final Map<Key, ManagedProcess> processesByKey = new HashMap<Key, ManagedProcess>();
    private final Set<Connection> managedConnections = new CopyOnWriteArraySet<Connection>();

    private volatile boolean shutdown;

    public static final short AUTH_BYTES_LENGTH = 16;
    public static final short AUTH_BYTES_ENCODED_LENGTH = 24;

    private final PrintStream stdout;
    private final PrintStream stderr;

    public ProcessController(final ProtocolServer.Configuration configuration, final PrintStream stdout, final PrintStream stderr) throws IOException {
        this.stdout = stdout;
        this.stderr = stderr;
        //noinspection ThisEscapedInObjectConstruction
        configuration.setConnectionHandler(new ProcessControllerServerHandler(this));
        final ProtocolServer server = new ProtocolServer(configuration);
        server.start();
        this.server = server;
    }

    void addManagedConnection(final Connection connection) {
        synchronized (lock)  {
            if(shutdown) {
                return;
            }
            managedConnections.add(connection);
        }
    }

    void removeManagedConnection(final Connection connection) {
        managedConnections.remove(connection);
    }

    public void addProcess(final String processName, final List<String> command, final Map<String, String> env, final String workingDirectory, final boolean isPrivileged, final boolean respawn) {
        // Create a new authKey
        final byte[] authBytes = new byte[ProcessController.AUTH_BYTES_LENGTH];
        new Random(new SecureRandom().nextLong()).nextBytes(authBytes);
        String authKey = Base64.getEncoder().encodeToString(authBytes);
        addProcess(processName, -1, authKey, command, env, workingDirectory, isPrivileged, respawn);
    }

    public void addProcess(final String processName, int id, final String authKey, final List<String> command, final Map<String, String> env, final String workingDirectory, final boolean isPrivileged, final boolean respawn) {
        for (String s : command) {
            if (s == null) {
                throw ProcessLogger.ROOT_LOGGER.nullCommandComponent();
            }
        }
        synchronized (lock) {
            if (shutdown) {
                return;
            }
            final Map<String, ManagedProcess> processes = this.processes;
            if (processes.containsKey(processName)) {
                ProcessLogger.ROOT_LOGGER.duplicateProcessName(processName);
                // ignore
                return;
            }
            final ManagedProcess process = new ManagedProcess(processName, id, command, env, workingDirectory, lock, this, authKey, isPrivileged, respawn);
            processes.put(processName, process);
            processesByKey.put(new Key(authKey.getBytes(StandardCharsets.US_ASCII)), process);
            processAdded(processName);
        }
    }

    public void startProcess(final String processName) {
        synchronized (lock) {
            if (shutdown) {
                return;
            }
            final Map<String, ManagedProcess> processes = this.processes;
            final ManagedProcess process = processes.get(processName);
            if (process == null) {
                ProcessLogger.ROOT_LOGGER.attemptToStartNonExistentProcess(processName);
                // ignore
                return;
            }
            process.start();
        }
    }

    public void stopProcess(final String processName) {
        synchronized (lock) {
            final Map<String, ManagedProcess> processes = this.processes;
            final ManagedProcess process = processes.get(processName);
            if (process == null) {
                ProcessLogger.ROOT_LOGGER.attemptToStopNonExistentProcess(processName);
                // ignore
                return;
            }
            process.stop();
        }
    }

    public void destroyProcess(final String processName) {
        synchronized (lock) {
            final Map<String, ManagedProcess> processes = this.processes;
            final ManagedProcess process = processes.get(processName);
            if (process == null) {
                // ignore
                return;
            }
            process.destroy();
        }
    }

    public void killProcess(final String processName) {
        synchronized (lock) {
            final Map<String, ManagedProcess> processes = this.processes;
            final ManagedProcess process = processes.get(processName);
            if (process == null) {
                // ignore
                return;
            }
            process.kill();
        }
    }

    public void removeProcess(final String processName) {
        synchronized (lock) {
            final Map<String, ManagedProcess> processes = this.processes;
            final ManagedProcess process = processes.get(processName);
            if (process == null) {
                ProcessLogger.ROOT_LOGGER.attemptToRemoveNonExistentProcess(processName);
                // ignore
                return;
            }
            boolean removed = processes.remove(processName) != null;
            processesByKey.remove(new Key(process.getAuthKey().getBytes(StandardCharsets.US_ASCII)));
            if(removed) {
                processRemoved(processName);
            }
            lock.notifyAll();
        }
    }

    public void sendStdin(final String recipient, final InputStream source) throws IOException {
        synchronized (lock) {
            if (shutdown) {
                return;
            }
            final Map<String, ManagedProcess> processes = this.processes;
            final ManagedProcess process = processes.get(recipient);
            if (process == null) {
                // ignore
                return;
            }
            process.sendStdin(source);
        }
    }

    public void shutdown() {
        synchronized (lock) {
            if (shutdown) {
                return;
            }
            ProcessLogger.ROOT_LOGGER.shuttingDown();
            shutdown = true;

            // In order to do a controlled shutdown we stop the host controller first
            // it will stop all managed servers and wait until they shutdown
            final ManagedProcess hc = processes.get(Main.HOST_CONTROLLER_PROCESS_NAME);
            if(hc != null && hc.isRunning()) {
                hc.shutdown();
                while(processes.containsKey(Main.HOST_CONTROLLER_PROCESS_NAME)) {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        // ignore
                    }
                }
            }
            // Shutdown remaining processes (if any)
            for (ManagedProcess process : processes.values()) {
                process.shutdown();
            }
            while (! processes.isEmpty()) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    // ignore
                }
            }
            ProcessLogger.ROOT_LOGGER.shutdownComplete();
        }
    }

    public ManagedProcess getServerByAuthCode(final byte[] code) {
        synchronized (lock) {
            return processesByKey.get(new Key(code));
        }
    }

    void processAdded(final String processName) {
        synchronized (lock) {
            for (Connection connection : managedConnections) {
                try {
                    final OutputStream os = connection.writeMessage();
                    try {
                        os.write(Protocol.PROCESS_ADDED);
                        StreamUtils.writeUTFZBytes(os, processName);
                        os.close();
                    } finally {
                        StreamUtils.safeClose(os);
                    }
                } catch (IOException e) {
                    ProcessLogger.ROOT_LOGGER.failedToWriteMessage("PROCESS_ADDED", e);
                }
            }
        }
    }

    void processStarted(final String processName) {
        synchronized (lock) {
            for (Connection connection : managedConnections) {
                try {
                    final OutputStream os = connection.writeMessage();
                    try {
                        os.write(Protocol.PROCESS_STARTED);
                        StreamUtils.writeUTFZBytes(os, processName);
                        os.close();
                    } finally {
                        StreamUtils.safeClose(os);
                    }
                } catch (IOException e) {
                    ProcessLogger.ROOT_LOGGER.failedToWriteMessage("PROCESS_STARTED", e);
                    removeManagedConnection(connection);
                }
            }
        }
    }

    void processStopped(final String processName, final long uptime) {
        synchronized (lock) {
            for (Connection connection : managedConnections) {
                try {
                    final OutputStream os = connection.writeMessage();
                    try {
                        os.write(Protocol.PROCESS_STOPPED);
                        StreamUtils.writeUTFZBytes(os, processName);
                        StreamUtils.writeLong(os, uptime);
                        os.close();
                    } finally {
                        StreamUtils.safeClose(os);
                    }
                } catch (IOException e) {
                    ProcessLogger.ROOT_LOGGER.failedToWriteMessage("PROCESS_STOPPED", e);
                    removeManagedConnection(connection);
                }
            }
        }
    }

    void processRemoved(final String processName) {
        synchronized (lock) {
            for (Connection connection : managedConnections) {
                try {
                    final OutputStream os = connection.writeMessage();
                    try {
                        os.write(Protocol.PROCESS_REMOVED);
                        StreamUtils.writeUTFZBytes(os, processName);
                        os.close();
                    } finally {
                        StreamUtils.safeClose(os);
                    }
                } catch (IOException e) {
                    ProcessLogger.ROOT_LOGGER.failedToWriteMessage("PROCESS_REMOVED " + processName, e);
                    removeManagedConnection(connection);
                }
            }
        }
    }

    void sendInventory() {
        synchronized (lock) {
            for (Connection connection : managedConnections) {
                try {
                    final OutputStream os = connection.writeMessage();
                    try {
                        os.write(Protocol.PROCESS_INVENTORY);
                        final Collection<ManagedProcess> processCollection = processes.values();
                        StreamUtils.writeInt(os, processCollection.size());
                        for (ManagedProcess process : processCollection) {
                            StreamUtils.writeUTFZBytes(os, process.getProcessName());
                            os.write(process.getAuthKey().getBytes(StandardCharsets.US_ASCII));
                            StreamUtils.writeBoolean(os, process.isRunning());
                            StreamUtils.writeBoolean(os, process.isStopping());
                        }
                        os.close();
                    } finally {
                        StreamUtils.safeClose(os);
                    }
                } catch (IOException e) {
                    ProcessLogger.ROOT_LOGGER.failedToWriteMessage("PROCESS_INVENTORY", e);
                    removeManagedConnection(connection);
                }
            }
        }
    }

    public void sendReconnectProcess(String processName, String scheme, String hostName, int port, boolean managementSubsystemEndpoint, String asAuthKey) {
        synchronized (lock) {
            ManagedProcess process = processes.get(processName);
            if (process == null) {
                ProcessLogger.ROOT_LOGGER.attemptToReconnectNonExistentProcess(processName);
                // ignore
                return;
            }
            process.reconnect(scheme, hostName, port, managementSubsystemEndpoint, asAuthKey);
        }
    }

    void operationFailed(final String processName, final ProcessMessageHandler.OperationType operationType) {
        synchronized (lock) {
            for (Connection connection : managedConnections) {
                try {
                    final OutputStream os = connection.writeMessage();
                    try {
                        os.write(Protocol.OPERATION_FAILED);
                        os.write(operationType.getCode());
                        StreamUtils.writeUTFZBytes(os, processName);
                        os.close();
                    } finally {
                        StreamUtils.safeClose(os);
                    }
                } catch (IOException e) {
                    ProcessLogger.ROOT_LOGGER.failedToWriteMessage("OPERATION_FAILED", e);
                    removeManagedConnection(connection);
                }
            }
        }
    }

    /**
     * Gets the current number of processes, or zero if a shutdown is in progress.
     *
     * @return the current number of processes, or zero if a shutdown is in progress
     */
    int getOngoingProcessCount() {
        return shutdown ? 0 : processes.size();
    }

    public ProtocolServer getServer() {
        return server;
    }

    PrintStream getStdout() {
        return stdout;
    }

    PrintStream getStderr() {
        return stderr;
    }

    private static final class Key {
        private final byte[] authKey;
        private final int hashCode;

        public Key(final byte[] authKey) {
            this.authKey = authKey;
            hashCode = Arrays.hashCode(authKey);
        }

        public boolean equals(Object other) {
            return other instanceof Key && equals((Key)other);
        }

        public boolean equals(Key other) {
            return this == other || other != null && Arrays.equals(authKey, other.authKey);
        }

        public int hashCode() {
            return hashCode;
        }
    }
}
