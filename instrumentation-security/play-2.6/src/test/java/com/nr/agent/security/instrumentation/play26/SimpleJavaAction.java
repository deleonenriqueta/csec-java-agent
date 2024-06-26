/*
 *
 *  * Copyright 2022 New Relic Corporation. All rights reserved.
 *  * SPDX-License-Identifier: Apache-2.0
 *
 */

package com.nr.agent.security.instrumentation.play26;


import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;

import play.Logger;
import play.libs.concurrent.Futures;
import play.libs.concurrent.HttpExecutionContext;
import play.mvc.Http;
import play.mvc.Result;
import play.mvc.Results;

import static play.mvc.Http.Status.GATEWAY_TIMEOUT;

public class SimpleJavaAction extends play.mvc.Action.Simple {
    private final Logger.ALogger logger = Logger.of("application.SimpleJavaAction");

    private final HttpExecutionContext ec;
    private final Futures futures;

    @Inject
    public SimpleJavaAction(HttpExecutionContext ec, Futures futures) {
        this.ec = ec;
        this.futures = futures;
    }

    public CompletionStage<Result> call(Http.Request req) {
        if (logger.isTraceEnabled()) {
            logger.trace("call: req = " + req);
        }

        return futures.timeout(doCall(req), 1L, TimeUnit.SECONDS).exceptionally(e ->
                (Results.status(GATEWAY_TIMEOUT, "No")));
    }

    private CompletionStage<Result> doCall(Http.Request req) {
        return delegate.call(req).handleAsync((result, e) -> {
            if (e != null) {
                if (e instanceof CompletionException) {
                    logger.error("Direct exception " + e.getMessage(), e);
                    return internalServerError();
                } else {
                    logger.error("Unknown exception " + e.getMessage(), e);
                    return internalServerError();
                }
            } else {
                return result;
            }
        }, ec.current());
    }
}