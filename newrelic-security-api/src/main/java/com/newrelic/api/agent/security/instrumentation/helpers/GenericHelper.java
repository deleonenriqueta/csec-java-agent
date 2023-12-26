package com.newrelic.api.agent.security.instrumentation.helpers;

import com.newrelic.api.agent.security.NewRelicSecurity;

import java.util.regex.Pattern;

public class GenericHelper {
    public static Pattern QUOTED_STRING_PATTERN = Pattern.compile("((\\\\)*?('|\\\"))(([\\s\\S]*?)(?:(?=(\\\\?))\\6.)*?)\\1",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    public static Pattern STORED_PROCEDURE_PATTERN = Pattern.compile("(call\\s+[a-zA-Z0-9_\\$]+\\(.*?\\))",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    public static final String CSEC_PARENT_ID = "nr-csec-parent-id";

    public static boolean skipExistsEvent() {
        if (!(NewRelicSecurity.getAgent().getCurrentPolicy().getVulnerabilityScan().getEnabled() &&
                NewRelicSecurity.getAgent().getCurrentPolicy().getVulnerabilityScan().getIastScan().getEnabled())) {
            return true;
        }

        return false;
    }

    private static String getNrSecCustomAttribName(String nrSecCustomAttrName, int hashCode) {
        return nrSecCustomAttrName + Thread.currentThread().getId() + hashCode;
    }

    public static boolean isLockAcquired(String nrSecCustomAttrName) {
        return isLockAcquired(nrSecCustomAttrName, 0);
    }

    public static boolean isLockAcquired(String nrSecCustomAttrName, int hashCode) {
        try {
            return NewRelicSecurity.isHookProcessingActive() &&
                    Boolean.TRUE.equals(NewRelicSecurity.getAgent().getSecurityMetaData().getCustomAttribute(getNrSecCustomAttribName(nrSecCustomAttrName, hashCode), Boolean.class));
        } catch (Throwable ignored) {}
        return false;
    }

    public static boolean acquireLockIfPossible(String nrSecCustomAttrName, int hashCode) {
        try {
            if (NewRelicSecurity.isHookProcessingActive() &&
                    !isLockAcquired(nrSecCustomAttrName, hashCode)) {
                NewRelicSecurity.getAgent().getSecurityMetaData().addCustomAttribute(getNrSecCustomAttribName(nrSecCustomAttrName, hashCode), true);
                return true;
            }
        } catch (Throwable ignored){}
        return false;
    }

    public static void releaseLock(String nrSecCustomAttrName, int hashCode) {
        try {
            if(NewRelicSecurity.isHookProcessingActive()) {
                NewRelicSecurity.getAgent().getSecurityMetaData().addCustomAttribute(getNrSecCustomAttribName(nrSecCustomAttrName, hashCode), null);
            }
        } catch (Throwable ignored){}
    }

    public static boolean acquireLockIfPossible(String nrSecCustomAttrName) {
        return acquireLockIfPossible(nrSecCustomAttrName, 0);
    }

    public static void releaseLock(String nrSecCustomAttrName) {
        releaseLock(nrSecCustomAttrName, 0);
    }
}
