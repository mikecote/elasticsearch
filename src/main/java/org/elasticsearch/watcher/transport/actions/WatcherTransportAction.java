/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher.transport.actions;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.master.MasterNodeRequest;
import org.elasticsearch.action.support.master.TransportMasterNodeAction;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.license.plugin.core.LicenseExpiredException;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.watcher.license.LicenseService;

/**
 *
 */
public abstract class WatcherTransportAction<Request extends MasterNodeRequest<Request>, Response extends ActionResponse> extends TransportMasterNodeAction<Request, Response> {

    private final LicenseService licenseService;

    public WatcherTransportAction(Settings settings, String actionName, TransportService transportService, ClusterService clusterService, ThreadPool threadPool, ActionFilters actionFilters, LicenseService licenseService,  Class<Request> request) {
        super(settings, actionName, transportService, clusterService, threadPool, actionFilters, request);
        this.licenseService = licenseService;
    }

    @Override
    protected void doExecute(Request request, ActionListener<Response> listener) {
        if (licenseService.enabled()) {
            super.doExecute(request, listener);
        } else {
            listener.onFailure(new LicenseExpiredException(LicenseService.FEATURE_NAME));
        }
    }
}
