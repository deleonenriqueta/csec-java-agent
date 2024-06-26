package org.springframework.web.reactive.result.method;

import com.newrelic.api.agent.security.schema.ApplicationURLMapping;
import com.newrelic.api.agent.security.instrumentation.helpers.*;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.reactive.result.condition.PatternsRequestCondition;
import org.springframework.web.util.pattern.PathPattern;

import java.lang.reflect.Method;

public class SpringHelper {
    private static final String WILDCARD = "*";
    public static <T> void gatherURLMappings(T mapping, Method method){
        try {
            RequestMappingInfo mappingInfo = (RequestMappingInfo) mapping;
            PatternsRequestCondition patternsCondition = mappingInfo.getPatternsCondition();
            if (patternsCondition != null) {
                for (PathPattern url : patternsCondition.getPatterns()) {
                    if (mappingInfo.getMethodsCondition().getMethods().isEmpty()) {
                        URLMappingsHelper.addApplicationURLMapping(new ApplicationURLMapping(WILDCARD, url.getPatternString(), method.getDeclaringClass().getName()));
                        continue;
                    }
                    for (RequestMethod requestMethod : mappingInfo.getMethodsCondition().getMethods()) {
                        URLMappingsHelper.addApplicationURLMapping(new ApplicationURLMapping(requestMethod.name(), url.getPatternString(), method.getDeclaringClass().getName()));
                    }
                }
            }
        } catch (Throwable ignored){
        }
    }
}
