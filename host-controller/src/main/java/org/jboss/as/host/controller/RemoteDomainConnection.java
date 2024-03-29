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

package org.jboss.as.host.controller;

import java.io.DataInput;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.SSLContext;
import javax.security.auth.callback.CallbackHandler;

import org.jboss.as.controller.RunningMode;
import org.jboss.as.controller.descriptions.ModelDescriptionConstants;
import org.jboss.as.controller.remote.TransactionalProtocolClient;
import org.jboss.as.domain.controller.SlaveRegistrationException;
import org.jboss.as.host.controller.discovery.DiscoveryOption;
import org.jboss.as.host.controller.discovery.RemoteDomainControllerConnectionConfiguration;
import org.jboss.as.host.controller.logging.HostControllerLogger;
import org.jboss.as.host.controller.mgmt.DomainControllerProtocol;
import org.jboss.as.protocol.ProtocolConnectionConfiguration;
import org.jboss.as.protocol.ProtocolConnectionManager;
import org.jboss.as.protocol.ProtocolConnectionUtils;
import org.jboss.as.protocol.StreamUtils;
import org.jboss.as.protocol.mgmt.AbstractManagementRequest;
import org.jboss.as.protocol.mgmt.ActiveOperation;
import org.jboss.as.protocol.mgmt.FlushableDataOutput;
import org.jboss.as.protocol.mgmt.FutureManagementChannel;
import org.jboss.as.protocol.mgmt.ManagementChannelHandler;
import org.jboss.as.protocol.mgmt.ManagementPingRequest;
import org.jboss.as.protocol.mgmt.ManagementPongRequestHandler;
import org.jboss.as.protocol.mgmt.ManagementRequestContext;
import org.jboss.as.remoting.management.ManagementRemotingServices;
import org.jboss.dmr.ModelNode;
import org.jboss.remoting3.Channel;
import org.jboss.remoting3.Connection;
import org.jboss.threads.AsyncFuture;
import org.wildfly.security.auth.client.AuthenticationContext;
import org.wildfly.security.manager.WildFlySecurityManager;

/**
 * A connection to a remote domain controller. Once successfully connected this {@code ManagementClientChannelStrategy}
 * implementation will try to reconnect with a remote host-controller.
 *
 * @author Emanuel Muckenhuber
 */
class RemoteDomainConnection extends FutureManagementChannel {

    private static final String CHANNEL_SERVICE_TYPE = ManagementRemotingServices.DOMAIN_CHANNEL;
    private static final long INTERVAL;
    private static final long TIMEOUT;

    static {
        long interval = -1;
        try {
            interval = Long.parseLong(WildFlySecurityManager.getPropertyPrivileged("jboss.as.domain.ping.interval", "15000"));
        } catch (Exception e) {
            // TODO log
        } finally {
            INTERVAL = interval > 0 ? interval : 15000;
        }
        long timeout = -1;
        try {
            timeout = Long.parseLong(WildFlySecurityManager.getPropertyPrivileged("jboss.as.domain.ping.timeout", "30000"));
        } catch (Exception e) {
            // TODO log
        } finally {
            TIMEOUT = timeout > 0 ? timeout : 30000;
        }
    }
    private final String localHostName;
    private final AuthenticationContext authenticationContext;

    private final RemoteDomainConnection.HostRegistrationCallback callback;
    private final ProtocolConnectionManager connectionManager;
    private final ProtocolConnectionConfiguration configuration;
    private final ManagementChannelHandler channelHandler;
    private final ExecutorService executorService;
    private final ScheduledExecutorService scheduledExecutorService;
    private final ManagementPongRequestHandler pongHandler = new ManagementPongRequestHandler();
    private final List<DiscoveryOption> discoveryOptions;
    private final RunningMode runningMode;
    private URI uri;

    RemoteDomainConnection(final String localHostName, final ProtocolConnectionConfiguration configuration, final AuthenticationContext authenticationContext,
                           final List<DiscoveryOption> discoveryOptions,
                           final ExecutorService executorService,
                           final ScheduledExecutorService scheduledExecutorService,
                           final HostRegistrationCallback callback,
                           final RunningMode runningMode) {
        this.callback = callback;
        this.localHostName = localHostName;
        this.configuration = configuration;
        this.authenticationContext = authenticationContext;
        this.discoveryOptions = discoveryOptions;
        this.executorService = executorService;
        this.channelHandler = new ManagementChannelHandler(this, executorService);
        this.scheduledExecutorService = scheduledExecutorService;
        this.runningMode = runningMode;
        this.connectionManager = ProtocolConnectionManager.create(new InitialConnectTask());
    }

    /**
     * Try to connect to the remote host.
     *
     * @throws IOException
     */
    protected void connect() throws IOException {
        // Connect to the remote HC
        connectionManager.connect();
    }

    /**
     * The channel handler.
     *
     * @return the channel handler
     */
    protected ManagementChannelHandler getChannelHandler() {
        return channelHandler;
    }

    @Override
    public Channel getChannel() throws IOException {
        return awaitChannel();
    }

    /**
     * Set the configuration uri.
     *
     * @param uri the uri
     */
    protected void setUri(URI uri) {
        this.uri = uri;
    }

    @Override
    public void close() throws IOException {
        try {
            if(prepareClose() && isConnected()) {
                try {
                    channelHandler.executeRequest(new UnregisterModelControllerRequest(), null).getResult().await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        } finally {
            try {
                super.close();
            } finally {
                connectionManager.shutdown();
            }
        }
    }

    protected boolean isConnected() {
        return super.isConnected();
    }

    /**
     * Connect and register at the remote domain controller.
     *
     * @return connection the established connection
     * @throws IOException
     */
    protected Connection openConnection() throws IOException {
        // Perhaps this can just be done once?
        CallbackHandler callbackHandler = null;
        SSLContext sslContext = null;

        final ProtocolConnectionConfiguration config = ProtocolConnectionConfiguration.copy(configuration);
        // TODO this needs cleaning up.
        config.setCallbackHandler(callbackHandler);
        config.setSslContext(sslContext);
        config.setUri(uri);

        AuthenticationContext authenticationContext = this.authenticationContext != null ? this.authenticationContext : AuthenticationContext.captureCurrent();

        // Connect
        try {
            return authenticationContext.run((PrivilegedExceptionAction<Connection>) () -> ProtocolConnectionUtils.connectSync(config));
        } catch (PrivilegedActionException e) {
            if (e.getCause() instanceof IOException) {
                throw (IOException) e.getCause();
            }
            throw new IOException(e);
        }
    }

    @Override
    public void connectionOpened(final Connection connection) throws IOException {
        final Channel channel = openChannel(connection, CHANNEL_SERVICE_TYPE, configuration.getOptionMap());
        if(setChannel(channel)) {
            channel.receiveMessage(channelHandler.getReceiver());
            channel.addCloseHandler(channelHandler);
            try {
                if (runningMode == RunningMode.ADMIN_ONLY) {
                    // Fetch the domain configuration
                    channelHandler.executeRequest(new FetchDomainConfigurationRequest(), null).getResult().get();
                } else {
                    // Start the registration process
                    channelHandler.executeRequest(new RegisterHostControllerRequest(), null).getResult().get();
                }
            } catch (Exception e) {
                if(e.getCause() instanceof IOException) {
                    throw (IOException) e.getCause();
                }
                throw new IOException(e);
            }
            // Registered
            registered();
        } else {
            channel.closeAsync();
        }
    }

    protected Future<Connection> reconnect() {
        // Reset the connection state
        channelHandler.getAttachments().removeAttachment(TransactionalProtocolClient.SEND_IDENTITY);
        return executorService.submit(new Callable<Connection>() {
            @Override
            public Connection call() throws Exception {
                final ReconnectPolicy reconnectPolicy = ReconnectPolicy.RECONNECT;
                int reconnectionCount = 0;
                for(;;) {
                    // Try to connect to the remote host controller by looping through all
                    // discovery options
                    reconnectPolicy.wait(reconnectionCount);
                    HostControllerLogger.ROOT_LOGGER.reconnectingToMaster();
                    for (Iterator<DiscoveryOption> i = discoveryOptions.iterator(); i.hasNext(); ) {
                        DiscoveryOption discoveryOption = i.next();
                        URI masterURI = null;
                        try {
                            List<RemoteDomainControllerConnectionConfiguration> remoteDcConfigs = discoveryOption.discover();
                            for (RemoteDomainControllerConnectionConfiguration remoteDcConfig : remoteDcConfigs) {
                                try {
                                    return connect(remoteDcConfig);
                                } catch (IOException ioe) {
                                    // If the cause is one of the irrecoverable ones, unwrap and throw it on
                                    RemoteDomainConnectionService.rethrowIrrecoverableConnectionFailures(ioe);
                                    // If we get here it's not irrecoverable, but we should note it
                                    HostControllerLogger.ROOT_LOGGER.debugf("Failed connecting to DomainController -- %s", ioe);
                                }
                            }
                        } catch (Exception e) {
                            RemoteDomainConnectionService.logConnectionException(masterURI, discoveryOption, i.hasNext(), e);
                        }
                    }
                    reconnectionCount++;
                }
            }
        });
    }

    private Connection connect(RemoteDomainControllerConnectionConfiguration remoteDcConfig) throws URISyntaxException, IOException, SlaveRegistrationException {
        URI masterURI = new URI(remoteDcConfig.getProtocol(), null, remoteDcConfig.getHost(), remoteDcConfig.getPort(), null, null, null);
        setUri(masterURI);
        HostControllerLogger.ROOT_LOGGER.debugf("trying to reconnect to remote host-controller at %s", masterURI);
        Connection connection = connectionManager.connect();
        HostControllerLogger.ROOT_LOGGER.connectedToMaster(masterURI);
        return connection;
    }

    /**
     * Resolve the subsystem versions.
     *
     * @param extensions the extensions
     * @return the resolved subsystem versions
     */
    ModelNode resolveSubsystemVersions(ModelNode extensions) {
        return callback.resolveSubsystemVersions(extensions);
    }

    /**
     * Apply the remote read domain model result.
     *
     * @param result the domain model result
     * @return whether it was applied successfully or not
     */
    boolean applyDomainModel(ModelNode result) {
        if(! result.hasDefined(ModelDescriptionConstants.RESULT)) {
            return false;
        }
        final List<ModelNode> bootOperations= result.get(ModelDescriptionConstants.RESULT).asList();
        return callback.applyDomainModel(bootOperations);
    }

    void registered() {
//        schedule(new PingTask());
        callback.registrationComplete(channelHandler);
    }

    private void schedule(PingTask task) {
        scheduledExecutorService.schedule(task, INTERVAL, TimeUnit.MILLISECONDS);
    }

    interface HostRegistrationCallback {

        /**
         * Get the versions for all registered subsystems.
         *
         * @param extensions the extension list
         * @return the subsystem versions
         */
        ModelNode resolveSubsystemVersions(ModelNode extensions);

        /**
         * Apply the remote domain model.
         *
         * @param result the read-domain-model operation result
         * @return {@code true} if the model was applied successfully, {@code false} otherwise
         */
        boolean applyDomainModel(List<ModelNode> result);

        /**
         * Event that the registration was completed.
         *
         * @param handler the handler
         */
        void registrationComplete(ManagementChannelHandler handler);

        /**
         * Recalculates metadata as needed to reflect any local changes since boot
         * @return the current host metadata
         */
        ModelNode createLocalHostInfo();
    }

    /**
      * The host-controller registration request.
      */
     private abstract class HostControllerConnectRequest extends AbstractManagementRequest<Void, Void> {

         @Override
         protected void sendRequest(final ActiveOperation.ResultHandler<Void> resultHandler, final ManagementRequestContext<Void> context, final FlushableDataOutput output) throws IOException {
             output.write(DomainControllerProtocol.PARAM_HOST_ID);
             output.writeUTF(localHostName);
             ModelNode hostInfo = callback.createLocalHostInfo();
             hostInfo.get(RemoteDomainConnectionService.DOMAIN_CONNECTION_ID).set(pongHandler.getConnectionId());
             hostInfo.writeExternal(output);
         }

         @Override
         public void handleRequest(final DataInput input, final ActiveOperation.ResultHandler<Void> resultHandler, final ManagementRequestContext<Void> context) throws IOException {
             byte param = input.readByte();
             // If it failed
             if(param != DomainControllerProtocol.PARAM_OK) {
                 final byte errorCode = input.readByte();
                 final String message =  input.readUTF();
                 resultHandler.failed(new SlaveRegistrationException(SlaveRegistrationException.ErrorCode.parseCode(errorCode), message));
                 return;
             }
             final ModelNode extensions = new ModelNode();
             extensions.readExternal(input);
             // Enable the send subject
             if (context.getRequestHeader().getVersion() != 1) {
                 channelHandler.getAttachments().attach(TransactionalProtocolClient.SEND_IDENTITY, Boolean.TRUE);
             }
             context.executeAsync(new ManagementRequestContext.AsyncTask<Void>() {
                 @Override
                 public void execute(ManagementRequestContext<Void> voidManagementRequestContext) throws Exception {
                     //
                     final ModelNode subsystems = resolveSubsystemVersions(extensions);
                     channelHandler.executeRequest(context.getOperationId(),
                             new RegisterSubsystemsRequest(subsystems));
                 }
             });
         }
     }

    /**
     * The host-controller registration request.
     */
    private class RegisterHostControllerRequest extends HostControllerConnectRequest {

        @Override
        public byte getOperationType() {
            return DomainControllerProtocol.REGISTER_HOST_CONTROLLER_REQUEST;
        }
    }

    /**
     * The host-controller fetch domain config request.
     */
    private class FetchDomainConfigurationRequest extends HostControllerConnectRequest {

        @Override
        public byte getOperationType() {
            return DomainControllerProtocol.FETCH_DOMAIN_CONFIGURATION_REQUEST;
        }
    }

     private class RegisterSubsystemsRequest extends AbstractManagementRequest<Void, Void> {

         private final ModelNode subsystems;

         private RegisterSubsystemsRequest(ModelNode subsystems) {
             this.subsystems = subsystems;
         }

         @Override
         public byte getOperationType() {
             return DomainControllerProtocol.REQUEST_SUBSYSTEM_VERSIONS;
         }

         @Override
         protected void sendRequest(ActiveOperation.ResultHandler<Void> registrationResultResultHandler, ManagementRequestContext<Void> voidManagementRequestContext, FlushableDataOutput output) throws IOException {
             output.writeByte(DomainControllerProtocol.PARAM_OK);
             subsystems.writeExternal(output);
         }

         @Override
         public void handleRequest(final DataInput input, final ActiveOperation.ResultHandler<Void> resultHandler, final ManagementRequestContext<Void> context) throws IOException {
             byte param = input.readByte();
             // If it failed
             if(param != DomainControllerProtocol.PARAM_OK) {
                 final byte errorCode = input.readByte();
                 final String message =  input.readUTF();
                 resultHandler.failed(new SlaveRegistrationException(SlaveRegistrationException.ErrorCode.parseCode(errorCode), message));
                 return;
             }
             final ModelNode domainModel = new ModelNode();
             domainModel.readExternal(input);
             context.executeAsync(new ManagementRequestContext.AsyncTask<Void>() {
                 @Override
                 public void execute(ManagementRequestContext<Void> voidManagementRequestContext) throws Exception {
                     // Apply the domain model
                     if (applyDomainModel(domainModel)) {
                         channelHandler.executeRequest(context.getOperationId(), new CompleteRegistrationRequest(DomainControllerProtocol.PARAM_OK));
                     } else {
                         channelHandler.executeRequest(context.getOperationId(), new CompleteRegistrationRequest(DomainControllerProtocol.PARAM_ERROR));
                         resultHandler.failed(new SlaveRegistrationException(SlaveRegistrationException.ErrorCode.UNKNOWN, ""));
                     }
                 }
             });
         }
     }

     private class CompleteRegistrationRequest extends AbstractManagementRequest<Void, Void> {

         private final byte outcome;
         private final String message = "yay!"; //

         private CompleteRegistrationRequest(final byte outcome) {
             this.outcome = outcome;
         }

         @Override
         public byte getOperationType() {
             return DomainControllerProtocol.COMPLETE_HOST_CONTROLLER_REGISTRATION;
         }

         @Override
         protected void sendRequest(final ActiveOperation.ResultHandler<Void> resultHandler, final ManagementRequestContext<Void> context, final FlushableDataOutput output) throws IOException {
             output.writeByte(outcome);
             output.writeUTF(message);
         }

         @Override
         public void handleRequest(DataInput input, ActiveOperation.ResultHandler<Void> resultHandler, ManagementRequestContext<Void> voidManagementRequestContext) throws IOException {
             final byte param = input.readByte();
             // If it failed
             if(param != DomainControllerProtocol.PARAM_OK) {
                 final byte errorCode = input.readByte();
                 final String message =  input.readUTF();
                 resultHandler.failed(new SlaveRegistrationException(SlaveRegistrationException.ErrorCode.parseCode(errorCode), message));
                 return;
             }
             resultHandler.done(null);
         }
     }

    private class UnregisterModelControllerRequest extends AbstractManagementRequest<Void, Void> {

        @Override
        public byte getOperationType() {
            return DomainControllerProtocol.UNREGISTER_HOST_CONTROLLER_REQUEST;
        }

        @Override
        protected void sendRequest(ActiveOperation.ResultHandler<Void> resultHandler, ManagementRequestContext<Void> voidManagementRequestContext, FlushableDataOutput output) throws IOException {
            output.write(DomainControllerProtocol.PARAM_HOST_ID);
            output.writeUTF(localHostName);
        }

        @Override
        public void handleRequest(DataInput input, ActiveOperation.ResultHandler<Void> resultHandler, ManagementRequestContext<Void> voidManagementRequestContext) throws IOException {
            HostControllerLogger.ROOT_LOGGER.unregisteredAtRemoteHostController();
            resultHandler.done(null);
        }

    }

    private class PingTask implements Runnable {

        private Long remoteInstanceID;

        @Override
        public void run() {
            if (isConnected()) {
                boolean fail = false;
                AsyncFuture<Long> future = null;
                try {
                    if (System.currentTimeMillis() - channelHandler.getLastMessageReceivedTime() > INTERVAL) {
                        future = channelHandler.executeRequest(ManagementPingRequest.INSTANCE, null).getResult();
                        Long id = future.get(TIMEOUT, TimeUnit.MILLISECONDS);
                        if (remoteInstanceID != null && !remoteInstanceID.equals(id)) {
                            HostControllerLogger.ROOT_LOGGER.masterHostControllerChanged();
                            fail = true;
                        } else {
                            remoteInstanceID = id;
                        }
                    }
                } catch (IOException e) {
                    HostControllerLogger.ROOT_LOGGER.debug("Caught exception sending ping request", e);
                } catch (InterruptedException e) {
                    safeCancel(future);
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    HostControllerLogger.ROOT_LOGGER.debug("Caught exception sending ping request", e);
                } catch (TimeoutException e) {
                    fail = true;
                    safeCancel(future);
                    HostControllerLogger.ROOT_LOGGER.masterHostControllerUnreachable(TIMEOUT);
                } finally {
                    if (fail) {
                        Channel channel = null;
                        try {
                            channel = channelHandler.getChannel();
                        } catch (IOException e) {
                            // ignore; shouldn't happen as the channel is already established if this task is running
                        }
                        StreamUtils.safeClose(channel);
                    } else {
                        schedule(this);
                    }
                }
            }
        }

        void safeCancel(Future<?> future) {
            if (future != null) {
                future.cancel(true);
            }
        }
    }

    class InitialConnectTask implements ProtocolConnectionManager.ConnectTask {

        @Override
        public Connection connect() throws IOException {
            return openConnection();
        }

        @Override
        public ProtocolConnectionManager.ConnectionOpenHandler getConnectionOpenedHandler() {
            return RemoteDomainConnection.this;
        }

        @Override
        public ProtocolConnectionManager.ConnectTask connectionClosed() {
            HostControllerLogger.ROOT_LOGGER.lostRemoteDomainConnection();
            return new ReconnectTaskWrapper(reconnect());
        }

        @Override
        public void shutdown() {
            //
        }
    }

    class ReconnectTaskWrapper implements ProtocolConnectionManager.ConnectTask {

        private final Future<Connection> connectionFuture;
        ReconnectTaskWrapper(Future<Connection> connectionFuture) {
            this.connectionFuture = connectionFuture;
        }

        @Override
        public Connection connect() throws IOException {
            return openConnection();
        }

        @Override
        public ProtocolConnectionManager.ConnectionOpenHandler getConnectionOpenedHandler() {
            return RemoteDomainConnection.this;
        }

        @Override
        public ProtocolConnectionManager.ConnectTask connectionClosed() {
            HostControllerLogger.ROOT_LOGGER.lostRemoteDomainConnection();
            return new ReconnectTaskWrapper(reconnect());
        }

        @Override
        public void shutdown() {
            connectionFuture.cancel(true);
        }
    }

}
