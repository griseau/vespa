// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.testutils;

import com.google.inject.Inject;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.HostName;
import com.yahoo.vespa.service.monitor.DuperModelInfraApi;
import com.yahoo.vespa.service.monitor.InfraApplicationApi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author hakonhall
 */
public class MockDuperModel implements DuperModelInfraApi {
    private final Map<ApplicationId, InfraApplicationApi> supportedInfraApps = new HashMap<>();
    private final ConcurrentHashMap<ApplicationId, List<HostName>> activeApps = new ConcurrentHashMap<>();

    @Inject
    public MockDuperModel() {
    }

    @Override
    public List<InfraApplicationApi> getSupportedInfraApplications() {
        return new ArrayList<>(supportedInfraApps.values());
    }

    @Override
    public Optional<InfraApplicationApi> getInfraApplication(ApplicationId applicationId) {
        return Optional.ofNullable(supportedInfraApps.get(applicationId));
    }

    @Override
    public boolean infraApplicationIsActive(ApplicationId applicationId) {
        return activeApps.containsKey(applicationId);
    }

    @Override
    public void infraApplicationActivated(ApplicationId applicationId, List<HostName> hostnames) {
        activeApps.put(applicationId, hostnames);
    }

    @Override
    public void infraApplicationRemoved(ApplicationId applicationId) {
        activeApps.remove(applicationId);
    }

    @Override
    public void infraApplicationsIsNowComplete() {
    }
}
