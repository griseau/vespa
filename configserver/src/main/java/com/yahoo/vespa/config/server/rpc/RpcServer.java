// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.config.server.rpc;

import com.google.inject.Inject;
import com.yahoo.cloud.config.ConfigserverConfig;
import com.yahoo.concurrent.ThreadFactoryFactory;
import com.yahoo.config.FileReference;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.HostLivenessTracker;
import com.yahoo.config.provision.TenantName;
import com.yahoo.component.Version;
import com.yahoo.jrt.Acceptor;
import com.yahoo.jrt.DataValue;
import com.yahoo.jrt.Int32Value;
import com.yahoo.jrt.Int64Value;
import com.yahoo.jrt.ListenFailedException;
import com.yahoo.jrt.Method;
import com.yahoo.jrt.Request;
import com.yahoo.jrt.Spec;
import com.yahoo.jrt.StringValue;
import com.yahoo.jrt.Supervisor;
import com.yahoo.jrt.Target;
import com.yahoo.jrt.Transport;
import com.yahoo.log.LogLevel;
import com.yahoo.vespa.config.ErrorCode;
import com.yahoo.vespa.config.JRTMethods;
import com.yahoo.vespa.config.protocol.ConfigResponse;
import com.yahoo.vespa.config.protocol.JRTServerConfigRequest;
import com.yahoo.vespa.config.protocol.JRTServerConfigRequestV3;
import com.yahoo.vespa.config.protocol.Trace;
import com.yahoo.vespa.config.server.SuperModelRequestHandler;
import com.yahoo.vespa.config.server.application.ApplicationSet;
import com.yahoo.vespa.config.server.GetConfigContext;
import com.yahoo.vespa.config.server.filedistribution.FileServer;
import com.yahoo.vespa.config.server.host.HostRegistries;
import com.yahoo.vespa.config.server.host.HostRegistry;
import com.yahoo.vespa.config.server.ReloadListener;
import com.yahoo.vespa.config.server.RequestHandler;
import com.yahoo.vespa.config.server.monitoring.MetricUpdater;
import com.yahoo.vespa.config.server.monitoring.MetricUpdaterFactory;
import com.yahoo.vespa.config.server.tenant.TenantHandlerProvider;
import com.yahoo.vespa.config.server.tenant.TenantListener;
import com.yahoo.vespa.config.server.tenant.TenantRepository;
import com.yahoo.vespa.filedistribution.FileDownloader;
import com.yahoo.vespa.filedistribution.FileReceiver;
import com.yahoo.vespa.filedistribution.FileReferenceData;
import com.yahoo.vespa.filedistribution.FileReferenceDownload;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * An RPC server class that handles the config protocol RPC method "getConfigV3".
 * Mandatory hooks need to be implemented by subclasses.
 *
 * @author hmusum
 */
// TODO: Split business logic out of this
public class RpcServer implements Runnable, ReloadListener, TenantListener {

    static final String getConfigMethodName = "getConfigV3";
    
    private static final int TRACELEVEL = 6;
    static final int TRACELEVEL_DEBUG = 9;
    private static final String THREADPOOL_NAME = "rpcserver worker pool";
    private static final long SHUTDOWN_TIMEOUT = 60;
    private static final int JRT_RPC_TRANSPORT_THREADS = 4;

    private final Supervisor supervisor = new Supervisor(new Transport(JRT_RPC_TRANSPORT_THREADS));
    private Spec spec;
    private final boolean useRequestVersion;
    private final boolean hostedVespa;
    private final boolean canReturnEmptySentinelConfig;

    private static final Logger log = Logger.getLogger(RpcServer.class.getName());

    private final DelayedConfigResponses delayedConfigResponses;

    private final HostRegistry<TenantName> hostRegistry;
    private final Map<TenantName, TenantHandlerProvider> tenantProviders = new ConcurrentHashMap<>();
    private final Map<ApplicationId, ApplicationState> applicationStateMap = new ConcurrentHashMap<>();
    private final SuperModelRequestHandler superModelRequestHandler;
    private final MetricUpdater metrics;
    private final MetricUpdaterFactory metricUpdaterFactory;
    private final HostLivenessTracker hostLivenessTracker;
    private final FileServer fileServer;

    private final ThreadPoolExecutor executorService;
    private final FileDownloader downloader;
    private volatile boolean allTenantsLoaded = false;
    private boolean isRunning = false;

    static class ApplicationState {
        private final AtomicLong activeGeneration = new AtomicLong(0);
        ApplicationState(long generation) {
            activeGeneration.set(generation);
        }
        long getActiveGeneration() { return activeGeneration.get(); }
        void setActiveGeneration(long generation) { activeGeneration.set(generation); }
    }
    /**
     * Creates an RpcServer listening on the specified <code>port</code>.
     *
     * @param config The config to use for setting up this server
     */
    @Inject
    public RpcServer(ConfigserverConfig config, SuperModelRequestHandler superModelRequestHandler,
                     MetricUpdaterFactory metrics, HostRegistries hostRegistries,
                     HostLivenessTracker hostLivenessTracker, FileServer fileServer) {
        this.superModelRequestHandler = superModelRequestHandler;
        metricUpdaterFactory = metrics;
        supervisor.setMaxOutputBufferSize(config.maxoutputbuffersize());
        this.metrics = metrics.getOrCreateMetricUpdater(Collections.emptyMap());
        this.hostLivenessTracker = hostLivenessTracker;
        BlockingQueue<Runnable> workQueue = new LinkedBlockingQueue<>(config.maxgetconfigclients());
        int numberOfRpcThreads = (config.numRpcThreads() == 0)
                ? Math.max(8, Runtime.getRuntime().availableProcessors())
                : config.numRpcThreads();
        executorService = new ThreadPoolExecutor(numberOfRpcThreads, numberOfRpcThreads,
                0, TimeUnit.SECONDS, workQueue, ThreadFactoryFactory.getThreadFactory(THREADPOOL_NAME));
        delayedConfigResponses = new DelayedConfigResponses(this, config.numDelayedResponseThreads());
        spec = new Spec(null, config.rpcport());
        hostRegistry = hostRegistries.getTenantHostRegistry();
        this.useRequestVersion = config.useVespaVersionInRequest();
        this.hostedVespa = config.hostedVespa();
        this.canReturnEmptySentinelConfig = config.canReturnEmptySentinelConfig();
        this.fileServer = fileServer;
        downloader = fileServer.downloader();
        setUpHandlers();
    }

    /**
     * Handles RPC method "config.v3.getConfig" requests.
     * Uses the template pattern to call methods in classes that extend RpcServer.
     */
    private void getConfigV3(Request req) {
        if (log.isLoggable(LogLevel.SPAM)) {
            log.log(LogLevel.SPAM, getConfigMethodName);
        }
        req.detach();
        addToRequestQueue(JRTServerConfigRequestV3.createFromRequest(req));
    }

    /**
     * Returns 0 if server is alive.
     */
    private void ping(Request req) {
        req.returnValues().add(new Int32Value(0));
    }

    /**
     * Returns a String with statistics data for the server.
     *
     * @param req a Request
     */
    private void printStatistics(Request req) {
        req.returnValues().add(new StringValue("Delayed responses queue size: " + delayedConfigResponses.size()));
    }

    @Override
    public void run() {
        log.log(LogLevel.INFO, "Rpc will listen on port " + spec.port());
        try {
            Acceptor acceptor = supervisor.listen(spec);
            isRunning = true;
            supervisor.transport().join();
            acceptor.shutdown().join();
        } catch (ListenFailedException e) {
            stop();
            throw new RuntimeException("Could not listen at " + spec, e);
        }
    }

    public void stop() {
        executorService.shutdown();
        try {
            executorService.awaitTermination(SHUTDOWN_TIMEOUT, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.interrupted(); // Ignore and continue shutdown.
        }
        delayedConfigResponses.stop();
        supervisor.transport().shutdown().join();
        isRunning = false;
    }

    public boolean isRunning() {
        return isRunning;
    }

    /**
     * Set up RPC method handlers.
     */
    private void setUpHandlers() {
        // The getConfig method in this class will handle RPC calls for getting config
        getSupervisor().addMethod(JRTMethods.createConfigV3GetConfigMethod(this::getConfigV3));
        getSupervisor().addMethod(new Method("ping", "", "i", this::ping)
                                  .methodDesc("ping")
                                  .returnDesc(0, "ret code", "return code, 0 is OK"));
        getSupervisor().addMethod(new Method("printStatistics", "", "s", this::printStatistics)
                                  .methodDesc("printStatistics")
                                  .returnDesc(0, "statistics", "Statistics for server"));
        getSupervisor().addMethod(new Method("filedistribution.serveFile", "si", "is", this::serveFile));
        getSupervisor().addMethod(new Method("filedistribution.setFileReferencesToDownload", "S", "i", this::setFileReferencesToDownload)
                                     .methodDesc("set which file references to download")
                                     .paramDesc(0, "file references", "file reference to download")
                                     .returnDesc(0, "ret", "0 if success, 1 otherwise"));
    }

    private ApplicationState getState(ApplicationId id) {
        ApplicationState state = applicationStateMap.get(id);
        if (state == null) {
            applicationStateMap.putIfAbsent(id, new ApplicationState(0));
            state = applicationStateMap.get(id);
        }
        return state;
    }
    boolean hasNewerGeneration(ApplicationId id, long generation) {
        return getState(id).getActiveGeneration() > generation;
    }
    /**
     * Checks all delayed responses for config changes and waits until all has been answered.
     * This method should be called when config is reloaded in the server.
     */
    @Override
    public void configActivated(ApplicationSet applicationSet) {
        ApplicationId applicationId = applicationSet.getId();
        ApplicationState state = getState(applicationId);
        state.setActiveGeneration(applicationSet.getApplicationGeneration());
        configReloaded(applicationId);
        reloadSuperModel(applicationSet);
    }

    private void reloadSuperModel(ApplicationSet applicationSet) {
        superModelRequestHandler.reloadConfig(applicationSet);
        configReloaded(ApplicationId.global());
    }

    void configReloaded(ApplicationId applicationId) {
        List<DelayedConfigResponses.DelayedConfigResponse> responses = delayedConfigResponses.drainQueue(applicationId);
        String logPre = TenantRepository.logPre(applicationId);
        if (log.isLoggable(LogLevel.DEBUG)) {
            log.log(LogLevel.DEBUG, logPre + "Start of configReload: " + responses.size() + " requests on delayed requests queue");
        }
        int responsesSent = 0;
        CompletionService<Boolean> completionService = new ExecutorCompletionService<>(executorService);
        while (!responses.isEmpty()) {
            DelayedConfigResponses.DelayedConfigResponse delayedConfigResponse = responses.remove(0);
            // Discard the ones that we have already answered
            // Doing cancel here deals with the case where the timer is already running or has not run, so
            // there is no need for any extra check.
            if (delayedConfigResponse.cancel()) {
                if (log.isLoggable(LogLevel.DEBUG)) {
                    logRequestDebug(LogLevel.DEBUG, logPre + "Timer cancelled for ", delayedConfigResponse.request);
                }
                // Do not wait for this request if we were unable to execute
                if (addToRequestQueue(delayedConfigResponse.request, false, completionService)) {
                    responsesSent++;
                }
            } else {
                log.log(LogLevel.DEBUG, logPre + "Timer already cancelled or finished or never scheduled");
            }
        }

        for (int i = 0; i < responsesSent; i++) {
            try {
                completionService.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        log.log(LogLevel.DEBUG, logPre + "Finished reloading " + responsesSent + " requests");
    }

    private void logRequestDebug(LogLevel level, String message, JRTServerConfigRequest request) {
        if (log.isLoggable(level)) {
            log.log(level, message + request.getShortDescription());
        }
    }

    @Override
    public void hostsUpdated(TenantName tenant, Collection<String> newHosts) {
        log.log(LogLevel.DEBUG, "Updating hosts in tenant host registry '" + hostRegistry + "' with " + newHosts);
        hostRegistry.update(tenant, newHosts);
    }

    @Override
    public void verifyHostsAreAvailable(TenantName tenant, Collection<String> newHosts) {
        hostRegistry.verifyHosts(tenant, newHosts);
    }

    @Override
    public void applicationRemoved(ApplicationId applicationId) {
        superModelRequestHandler.removeApplication(applicationId);
        configReloaded(applicationId);
        configReloaded(ApplicationId.global());
    }

    public void respond(JRTServerConfigRequest request) {
        if (log.isLoggable(LogLevel.DEBUG)) {
            log.log(LogLevel.DEBUG, "Trace at request return:\n" + request.getRequestTrace().toString());
        }
        request.getRequest().returnRequest();
    }

    /**
     * Returns the tenant for this request, empty if there is no tenant for this request
     * (which on hosted Vespa means that the requesting host is not currently active for any tenant)
     */
    Optional<TenantName> resolveTenant(JRTServerConfigRequest request, Trace trace) {
        if ("*".equals(request.getConfigKey().getConfigId())) return Optional.of(ApplicationId.global().tenant());
        String hostname = request.getClientHostName();
        TenantName tenant = hostRegistry.getKeyForHost(hostname);
        if (tenant == null) {
            if (GetConfigProcessor.logDebug(trace)) {
                String message = "Did not find tenant for host '" + hostname + "', using " + TenantName.defaultName();
                log.log(LogLevel.DEBUG, message);
                log.log(LogLevel.DEBUG, "hosts in host registry: " + hostRegistry.getAllHosts());
                trace.trace(6, message);
            }
            return Optional.empty();
        }
        return Optional.of(tenant);
    }

    public ConfigResponse resolveConfig(JRTServerConfigRequest request, GetConfigContext context, Optional<Version> vespaVersion) {
        context.trace().trace(TRACELEVEL, "RpcServer.resolveConfig()");
        return context.requestHandler().resolveConfig(context.applicationId(), request, vespaVersion);
    }

    private Supervisor getSupervisor() {
        return supervisor;
    }

    private void addToRequestQueue(JRTServerConfigRequest request) {
        addToRequestQueue(request, false, null);
    }

    public Boolean addToRequestQueue(JRTServerConfigRequest request, boolean forceResponse, CompletionService<Boolean> completionService) {
        // It's no longer delayed if we get here
        request.setDelayedResponse(false);
        //ConfigDebug.logDebug(log, System.currentTimeMillis(), request.getConfigKey(), "RpcServer.addToRequestQueue()");
        try {
            final GetConfigProcessor task = new GetConfigProcessor(this, request, forceResponse);
            if (completionService == null) {
                executorService.submit(task);
            } else {
                completionService.submit(() -> { task.run();return true;});
            }
            updateWorkQueueMetrics();
            return true;
        } catch (RejectedExecutionException e) {
            request.addErrorResponse(ErrorCode.INTERNAL_ERROR, "getConfig request queue size is larger than configured max limit");
            respond(request);
            return false;
        }
    }

    private void updateWorkQueueMetrics() {
        int queued = executorService.getQueue().size();
        metrics.setRpcServerQueueSize(queued);
    }

    /**
     * Returns the context for this request, or null if the server is not properly set up with handlers
     */
    GetConfigContext createGetConfigContext(Optional<TenantName> optionalTenant, JRTServerConfigRequest request, Trace trace) {
        if ("*".equals(request.getConfigKey().getConfigId())) {
            return GetConfigContext.create(ApplicationId.global(), superModelRequestHandler, trace);
        }
        TenantName tenant = optionalTenant.orElse(TenantName.defaultName()); // perhaps needed for non-hosted?
        if ( ! hasRequestHandler(tenant)) {
            String msg = TenantRepository.logPre(tenant) + "Unable to find request handler for tenant. Requested from host '" + request.getClientHostName() + "'";
            metrics.incUnknownHostRequests();
            trace.trace(TRACELEVEL, msg);
            log.log(LogLevel.WARNING, msg);
            return null;
        }
        RequestHandler handler = getRequestHandler(tenant);
        ApplicationId applicationId = handler.resolveApplicationId(request.getClientHostName());
        if (trace.shouldTrace(TRACELEVEL_DEBUG)) {
            trace.trace(TRACELEVEL_DEBUG, "Host '" + request.getClientHostName() + "' should have config from application '" + applicationId + "'");
        }
        return GetConfigContext.create(applicationId, handler, trace);
    }

    private boolean hasRequestHandler(TenantName tenant) {
        return tenantProviders.containsKey(tenant);
    }

    private RequestHandler getRequestHandler(TenantName tenant) {
        if (!tenantProviders.containsKey(tenant)) {
            throw new IllegalStateException("No request handler for " + tenant);
        }
        return tenantProviders.get(tenant).getRequestHandler();
    }

    void delayResponse(JRTServerConfigRequest request, GetConfigContext context) {
        delayedConfigResponses.delayResponse(request, context);
    }

    @Override
    public void onTenantDelete(TenantName tenant) {
        log.log(LogLevel.DEBUG, TenantRepository.logPre(tenant)+"Tenant deleted, removing request handler and cleaning host registry");
        tenantProviders.remove(tenant);
        hostRegistry.removeHostsForKey(tenant);
    }

    @Override
    public void onTenantsLoaded() {
        allTenantsLoaded = true;
        superModelRequestHandler.enable();
    }

    @Override
    public void onTenantCreate(TenantName tenant, TenantHandlerProvider tenantHandlerProvider) {
        log.log(LogLevel.DEBUG, TenantRepository.logPre(tenant)+"Tenant created, adding request handler");
        tenantProviders.put(tenant, tenantHandlerProvider);
    }

    /** Returns true only after all tenants are loaded */
    public boolean allTenantsLoaded() { return allTenantsLoaded; }

    /** Returns true if this rpc server is currently running in a hosted Vespa configuration */
    public boolean isHostedVespa() { return hostedVespa; }

    /** Returns true if empty sentinel config can be returned when a request from a host that is
     * not part of an application asks for sentinel config */
    public boolean canReturnEmptySentinelConfig() { return canReturnEmptySentinelConfig; }
    
    MetricUpdaterFactory metricUpdaterFactory() {
        return metricUpdaterFactory;
    }

    boolean useRequestVersion() {
        return useRequestVersion;
    }

    class ChunkedFileReceiver implements FileServer.Receiver {
        Target target;
        ChunkedFileReceiver(Target target) {
            this.target = target;
        }

        @Override
        public String toString() {
            return target.toString();
        }

        @Override
        public void receive(FileReferenceData fileData, FileServer.ReplayStatus status) {
            int session = sendMeta(fileData);
            sendParts(session, fileData);
            sendEof(session, fileData, status);
        }
        private void sendParts(int session, FileReferenceData fileData) {
            ByteBuffer bb = ByteBuffer.allocate(0x100000);
            for (int partId = 0, read = fileData.nextContent(bb); read >= 0; partId++, read = fileData.nextContent(bb)) {
                byte [] buf = bb.array();
                if (buf.length != bb.position()) {
                    buf = new byte [bb.position()];
                    bb.flip();
                    bb.get(buf);
                }
                sendPart(session, fileData.fileReference(), partId, buf);
                bb.clear();
            }
        }
        private int sendMeta(FileReferenceData fileData) {
            Request request = new Request(FileReceiver.RECEIVE_META_METHOD);
            request.parameters().add(new StringValue(fileData.fileReference().value()));
            request.parameters().add(new StringValue(fileData.filename()));
            request.parameters().add(new StringValue(fileData.type().name()));
            request.parameters().add(new Int64Value(fileData.size()));
            invokeRpcIfValidConnection(request);
            if (request.isError()) {
                log.warning("Failed delivering meta for reference '" + fileData.fileReference().value() + "' with file '" + fileData.filename() + "' to " +
                        target.toString() + " with error: '" + request.errorMessage() + "'.");
                return 1;
            } else {
                if (request.returnValues().get(0).asInt32() != 0) {
                    throw new IllegalArgumentException("Unknown error from target '" + target.toString() + "' during rpc call " + request.methodName());
                }
                return request.returnValues().get(1).asInt32();
            }
        }
        private void sendPart(int session, FileReference ref, int partId, byte [] buf) {
            Request request = new Request(FileReceiver.RECEIVE_PART_METHOD);
            request.parameters().add(new StringValue(ref.value()));
            request.parameters().add(new Int32Value(session));
            request.parameters().add(new Int32Value(partId));
            request.parameters().add(new DataValue(buf));
            invokeRpcIfValidConnection(request);
            if (request.isError()) {
                throw new IllegalArgumentException("Failed delivering reference '" + ref.value() + "' to " +
                                                           target.toString() + " with error: '" + request.errorMessage() + "'.");
            } else {
                if (request.returnValues().get(0).asInt32() != 0) {
                    throw new IllegalArgumentException("Unknown error from target '" + target.toString() + "' during rpc call " + request.methodName());
                }
            }
        }
        private void sendEof(int session, FileReferenceData fileData, FileServer.ReplayStatus status) {
            Request request = new Request(FileReceiver.RECEIVE_EOF_METHOD);
            request.parameters().add(new StringValue(fileData.fileReference().value()));
            request.parameters().add(new Int32Value(session));
            request.parameters().add(new Int64Value(fileData.xxhash()));
            request.parameters().add(new Int32Value(status.getCode()));
            request.parameters().add(new StringValue(status.getDescription()));
            invokeRpcIfValidConnection(request);
            if (request.isError()) {
                throw new IllegalArgumentException("Failed delivering reference '" + fileData.fileReference().value() + "' with file '" + fileData.filename() + "' to " +
                                                           target.toString() + " with error: '" + request.errorMessage() + "'.");
            } else {
                if (request.returnValues().get(0).asInt32() != 0) {
                    throw new IllegalArgumentException("Unknown error from target '" + target.toString() + "' during rpc call " + request.methodName());
                }
            }
        }

        private void invokeRpcIfValidConnection(Request request) {
            if (target.isValid()) {
                target.invokeSync(request, 600);
            } else {
                throw new RuntimeException("Connection to " + target + " is invalid", target.getConnectionLostReason());
            }
        }
    }

    private void serveFile(Request request) {
        request.detach();
        FileServer.Receiver receiver = new ChunkedFileReceiver(request.target());
        fileServer.serveFile(request.parameters().get(0).asString(), request.parameters().get(1).asInt32() == 0, request, receiver);
    }

    private void setFileReferencesToDownload(Request req) {
        String[] fileReferenceStrings = req.parameters().get(0).asStringArray();
        Stream.of(fileReferenceStrings)
                .map(FileReference::new)
                .forEach(fileReference -> downloader.downloadIfNeeded(
                        new FileReferenceDownload(fileReference, false /* downloadFromOtherSourceIfNotFound */)));
        req.returnValues().add(new Int32Value(0));
    }

    HostLivenessTracker hostLivenessTracker() {
        return hostLivenessTracker;
    }
}
