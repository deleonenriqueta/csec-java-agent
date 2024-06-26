/*
 *
 *  * Copyright 2020 New Relic Corporation. All rights reserved.
 *  * SPDX-License-Identifier: Apache-2.0
 *
 */

package com.newrelic.agent.security.instrumentation.springweb;

import com.newrelic.api.agent.security.instrumentation.helpers.ServletHelper;
import com.newrelic.api.agent.weaver.MatchType;
import com.newrelic.api.agent.weaver.WeaveIntoAllMethods;
import com.newrelic.api.agent.weaver.WeaveWithAnnotation;

@WeaveWithAnnotation(annotationClasses = {
        "org.springframework.stereotype.Controller",
        "org.springframework.web.bind.annotation.RestController"},
        type = MatchType.ExactClass)
public class SpringController_Instrumentation {

    @WeaveWithAnnotation(annotationClasses = {
            "org.springframework.web.bind.annotation.RequestMapping",
            "org.springframework.web.bind.annotation.PatchMapping",
            "org.springframework.web.bind.annotation.PutMapping",
            "org.springframework.web.bind.annotation.GetMapping",
            "org.springframework.web.bind.annotation.PostMapping",
            "org.springframework.web.bind.annotation.DeleteMapping"})
    @WeaveIntoAllMethods
    private static void requestMapping() {
        ServletHelper.registerUserLevelCode("spring-annotation");
        ServletHelper.setFoundAnnotedUserLevelServiceMethod();
    }

}
