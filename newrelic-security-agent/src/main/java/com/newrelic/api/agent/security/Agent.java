package com.newrelic.api.agent.security;

import com.fasterxml.jackson.dataformat.javaprop.JavaPropsMapper;
import com.newrelic.agent.security.AgentConfig;
import com.newrelic.agent.security.AgentInfo;
import com.newrelic.agent.security.instrumentator.dispatcher.DispatcherPool;
import com.newrelic.agent.security.instrumentator.os.OsVariablesInstance;
import com.newrelic.agent.security.instrumentator.utils.*;
import com.newrelic.agent.security.intcodeagent.constants.AgentServices;
import com.newrelic.agent.security.intcodeagent.filelogging.FileLoggerThreadPool;
import com.newrelic.agent.security.intcodeagent.filelogging.LogFileHelper;
import com.newrelic.agent.security.intcodeagent.utils.EncryptorUtils;
import com.newrelic.api.agent.security.instrumentation.helpers.*;
import com.newrelic.api.agent.security.utils.logging.LogLevel;
import com.newrelic.agent.security.intcodeagent.logging.HealthCheckScheduleThread;
import com.newrelic.agent.security.intcodeagent.logging.IAgentConstants;
import com.newrelic.agent.security.intcodeagent.models.javaagent.ExitEventBean;
import com.newrelic.agent.security.intcodeagent.properties.BuildInfo;
import com.newrelic.agent.security.intcodeagent.schedulers.FileCleaner;
import com.newrelic.agent.security.intcodeagent.schedulers.SchedulerHelper;
import com.newrelic.agent.security.intcodeagent.utils.CommonUtils;
import com.newrelic.agent.security.intcodeagent.websocket.*;
import com.newrelic.agent.security.util.IUtilConstants;
import com.newrelic.api.agent.NewRelic;
import com.newrelic.api.agent.Transaction;
import com.newrelic.api.agent.security.schema.*;
import com.newrelic.api.agent.security.schema.operation.RXSSOperation;
import com.newrelic.api.agent.security.schema.policy.AgentPolicy;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

import static com.newrelic.agent.security.intcodeagent.logging.IAgentConstants.*;

public class Agent implements SecurityAgent {

    private static final String EVENT_ZERO_PROCESSED = "[EVENT] First event processed : %s";
    public static final String CRITICAL_ERROR_UNABLE_TO_READ_BUILD_INFO_AND_VERSION_S_S = "CSEC Critical error. Unable to read buildInfo and version: %s : %s";
    public static final String CRITICAL_ERROR_UNABLE_TO_READ_BUILD_INFO_AND_VERSION = "CSEC Critical error. Unable to read buildInfo and version: ";

    public static final String DROPPING_EVENT_AS_IT_WAS_GENERATED_BY_K_2_INTERNAL_API_CALL = "Dropping event as it was generated by agent internal API call : ";
    private static final AtomicBoolean firstEventProcessed = new AtomicBoolean(false);

    private AgentInfo info;

    private AgentConfig config;

    private boolean isInitialised;

    private static FileLoggerThreadPool logger;

    private java.net.URL agentJarURL;
    private Instrumentation instrumentation;

    private static final class InstanceHolder {
        static final Agent instance = new Agent();
    }

    public static SecurityAgent getInstance() {
        return InstanceHolder.instance;
    }

    private Agent(){
        // TODO: All the record keeping or obj init tasks are to be performed here.
        /**
         * Object initializations
         *      App Info
         *      Health Check
         *      PID detection
         *      Set agent status
         * */
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "off");
        System.setProperty("org.slf4j.simpleLogger.logFile", "System.out");
    }

    private void initialise() {
        // TODO: All the bring up tasks are to be performed here.
        /**
         * 1. populate policy
         * 2. create application info
         * 3. initialise health check
         * 4. start following services
         * */

        //NOTE: The bellow call sequence is critical and dependent on each other
        if (!isInitialised()) {
            config = AgentConfig.getInstance();
            info = AgentInfo.getInstance();
        }
        config.instantiate();
        logger = FileLoggerThreadPool.getInstance();
        logger.logInit(
                LogLevel.INFO,
                "[STEP-1] => Security agent is starting",
                Agent.class.getName());
        logger.logInit(
                LogLevel.INFO,
                String.format("[STEP-2] => Generating unique identifier: %s", AgentInfo.getInstance().getApplicationUUID()), AgentInfo.class.getName());
        config.setConfig(CollectorConfigurationUtils.populateCollectorConfig());

        try {
            info.setBuildInfo(readCollectorBuildInfo());
            logger.log(LogLevel.INFO, String.format("CSEC Collector build info : %s", new JavaPropsMapper().writeValueAsProperties(info.getBuildInfo())), this.getClass().getName());
        } catch (IOException e) {
            // TODO: Need to confirm requirement of this throw.
            throw new RuntimeException("Unable to read CSEC Collector build info", e);
        }
        logger.logInit(
                LogLevel.INFO,
                "[STEP-3] => Gathering information about the application",
                this.getClass().getName());
        logger.logInit(LogLevel.INFO, NewRelic.getAgent().getConfig().getValue(LowSeverityHelper.LOW_SEVERITY_HOOKS_ENABLED, LowSeverityHelper.DEFAULT)?
                "Low priority instrumentations are enabled.":"Low priority instrumentations are disabled!", this.getClass().getName());
        if( NewRelic.getAgent().getConfig().getValue(IUtilConstants.NR_SECURITY_HOME_APP, false) ) {
            logger.logInit(LogLevel.INFO, "App being scanned is a Newrelic's Home Grown application", this.getClass().getName());
        }
        info.setIdentifier(ApplicationInfoUtils.envDetection());
        ApplicationInfoUtils.continueIdentifierProcessing(info.getIdentifier(), config.getConfig());
        info.generateAppInfo(config.getConfig());
        info.initialiseHC();
        config.populateAgentPolicy();
        config.populateAgentPolicyParameters();
        config.setupSnapshotDir();
        info.initStatusLogValues();
        setInitialised(true);
        populateLinkingMetadata();
        populateApplicationTmpDir();
        startK2Services();
        info.agentStatTrigger(true);
    }

    private void populateApplicationTmpDir() {
        String tmpDir = System.getProperty(IUtilConstants.JAVA_IO_TMPDIR);
        setServerInfo(IUtilConstants.APPLICATION_TMP_DIRECTORY, tmpDir);
    }

    private BuildInfo readCollectorBuildInfo() {
        BuildInfo buildInfo = new BuildInfo();
        try {
            JavaPropsMapper mapper = new JavaPropsMapper();
            buildInfo = mapper.
                    readValue(CommonUtils.getResourceStreamFromAgentJar("Agent.properties"), BuildInfo.class);
        } catch (Throwable e) {
            logger.log(LogLevel.SEVERE, String.format(CRITICAL_ERROR_UNABLE_TO_READ_BUILD_INFO_AND_VERSION_S_S, e.getMessage(), e.getCause()), this.getClass().getName());
            logger.postLogMessageIfNecessary(LogLevel.SEVERE,
                    String.format(CRITICAL_ERROR_UNABLE_TO_READ_BUILD_INFO_AND_VERSION_S_S, e.getMessage(), e.getCause()),
                    e, this.getClass().getName());
            logger.log(LogLevel.FINER, CRITICAL_ERROR_UNABLE_TO_READ_BUILD_INFO_AND_VERSION, e, this.getClass().getName());
        }
        return buildInfo;
    }

    private void populateLinkingMetadata() {
        Map<String, String> linkingMetaData = NewRelic.getAgent().getLinkingMetadata();
        linkingMetaData.put(INRSettingsKey.AGENT_RUN_ID_LINKING_METADATA, NewRelic.getAgent().getConfig().getValue(INRSettingsKey.AGENT_RUN_ID));
        info.setLinkingMetadata(linkingMetaData);
    }

    private void startK2Services() {
        HealthCheckScheduleThread.getInstance().scheduleNewTask();
        FileCleaner.scheduleNewTask();
        SchedulerHelper.getInstance().scheduleLowSeverityFilterCleanup(LowSeverityHelper::clearLowSeverityEventFilter,
                30 , 30, TimeUnit.MINUTES);
        SchedulerHelper.getInstance().scheduleDailyLogRollover(LogFileHelper::performDailyRollover);
        logger.logInit(
                LogLevel.INFO,
                String.format(STARTED_MODULE_LOG, AgentServices.HealthCheck.name()),
                Agent.class.getName()
        );
        WSReconnectionST.getInstance().submitNewTaskSchedule(0);
        EventSendPool.getInstance();
        logger.logInit(
                LogLevel.INFO,
                String.format(STARTED_MODULE_LOG, AgentServices.EventWritePool.name()),
                Agent.class.getName()
        );
        logger.logInit(LogLevel.INFO, AGENT_INIT_LOG_STEP_FIVE_END, Agent.class.getName());

    }

    @Override
    public boolean refreshState(java.net.URL agentJarURL, Instrumentation instrumentation) {
        /**
         * restart k2 services
         **/
        this.agentJarURL = agentJarURL;
        this.instrumentation = instrumentation;
        if (isInitialised()) {
            config.setNRSecurityEnabled(false);
            cancelActiveServiceTasks();
        }
        initialise();
        NewRelic.getAgent().getLogger().log(Level.INFO, "Security refresh was invoked, Security Agent initiation is successful.");
        return true;
    }

    private void cancelActiveServiceTasks() {

        /**
         * Drain the pools (RestClient, EventSend, Dispatcher) before websocket close
         * Websocket
         * policy
         * HealthCheck
         */
        WSClient.shutDownWSClient(false);
        HealthCheckScheduleThread.getInstance().cancelTask(true);
        FileCleaner.cancelTask();

    }

    @Override
    public boolean deactivateSecurity() {
        if(isInitialised()) {
            config.setNRSecurityEnabled(false);
            deactivateSecurityServices();
        }
        return true;
    }

    private void deactivateSecurityServices(){
        /**
         * ShutDown following
         * 1. policy + policy parameter
         * 2. websocket
         * 3. event pool
         * 4. HealthCheck
         **/
        HealthCheckScheduleThread.getInstance().cancelTask(true);
        FileCleaner.cancelTask();
        WSClient.shutDownWSClient(true);
        WSReconnectionST.shutDownPool();
        EventSendPool.shutDownPool();
    }

    @Override
    public void registerOperation(AbstractOperation operation) {
        // added to fetch request/response in case of grpc requests
        SecurityMetaData securityMetaData = NewRelicSecurity.getAgent().getSecurityMetaData();
        if (securityMetaData!=null && securityMetaData.getRequest().getIsGrpc()){
            securityMetaData.getRequest().setBody(
                    new StringBuilder(JsonConverter.toJSON(securityMetaData.getCustomAttribute(GrpcHelper.NR_SEC_GRPC_REQUEST_DATA, List.class))));
            securityMetaData.getResponse().setResponseBody(
                    new StringBuilder(JsonConverter.toJSON(securityMetaData.getCustomAttribute(GrpcHelper.NR_SEC_GRPC_RESPONSE_DATA, List.class))));
        }
        // end

        if (operation == null || operation.isEmpty()) {
            return;
        }
        String executionId = ExecutionIDGenerator.getExecutionId();
        operation.setExecutionId(executionId);
        operation.setStartTime(Instant.now().toEpochMilli());
        if(securityMetaData!=null && securityMetaData.getFuzzRequestIdentifier().getK2Request()){
            logger.log(LogLevel.FINEST, String.format("New Event generation with id %s of type %s", operation.getExecutionId(), operation.getClass().getSimpleName()), Agent.class.getName());
        }
        if (operation instanceof RXSSOperation) {
            operation.setStackTrace(securityMetaData.getMetaData().getServiceTrace());
            securityMetaData.addCustomAttribute("RXSS_PROCESSED", true);
        } else {
            StackTraceElement[] trace = Thread.currentThread().getStackTrace();
            operation.setStackTrace(Arrays.copyOfRange(trace, 2, trace.length));
        }

        // added to fetch request/response in case of grpc requests
        if (securityMetaData.getRequest().getIsGrpc()){
            securityMetaData.getRequest().setBody(
                    new StringBuilder(JsonConverter.toJSON(securityMetaData.getCustomAttribute(GrpcHelper.NR_SEC_GRPC_REQUEST_DATA, List.class))));
            securityMetaData.getResponse().setResponseBody(
                    new StringBuilder(JsonConverter.toJSON(securityMetaData.getCustomAttribute(GrpcHelper.NR_SEC_GRPC_RESPONSE_DATA, List.class))));
        }

        if(NewRelic.getAgent().getConfig().getValue(IUtilConstants.NR_SECURITY_HOME_APP, false) && checkIfCSECGeneratedEvent(operation)) {
            logger.log(LogLevel.FINEST, DROPPING_EVENT_AS_IT_WAS_GENERATED_BY_K_2_INTERNAL_API_CALL +
                            JsonConverter.toJSON(operation),
                    Agent.class.getName());
            return;
        }

        if(!NewRelic.getAgent().getConfig().getValue(IUtilConstants.NR_SECURITY_HOME_APP, false) && checkIfNRGeneratedEvent(operation)) {
            logger.log(LogLevel.FINEST, DROPPING_EVENT_AS_IT_WAS_GENERATED_BY_K_2_INTERNAL_API_CALL +
                            JsonConverter.toJSON(operation),
                    Agent.class.getName());
            return;
        }

        logIfIastScanForFirstTime(securityMetaData.getFuzzRequestIdentifier(), securityMetaData.getRequest());

        setRequiredStackTrace(operation, securityMetaData);
        operation.setUserClassEntity(setUserClassEntity(operation, securityMetaData));
        processStackTrace(operation);
//        boolean blockNeeded = checkIfBlockingNeeded(operation.getApiID());
//        securityMetaData.getMetaData().setApiBlocked(blockNeeded);
        if (needToGenerateEvent(operation.getApiID())) {
            DispatcherPool.getInstance().dispatchEvent(operation, securityMetaData);
            if (!firstEventProcessed.get()) {
                logger.logInit(LogLevel.INFO,
                        String.format(EVENT_ZERO_PROCESSED, securityMetaData.getRequest()),
                        this.getClass().getName());
                firstEventProcessed.set(true);
            }
        }
    }

    private boolean checkIfCSECGeneratedEvent(AbstractOperation operation) {
        for (int i = 1, j = 0; i < operation.getStackTrace().length; i++) {
            // Only remove consecutive top com.newrelic and com.nr. elements from stack.
            if (i - 1 == j && StringUtils.startsWithAny(operation.getStackTrace()[i].getClassName(), "com.newrelic.agent.security.", "com.newrelic.api.agent.")) {
                j++;
            } else if (StringUtils.startsWithAny(operation.getStackTrace()[i].getClassName(), "com.newrelic.agent.security.", "com.newrelic.api.agent.")) {
                return true;
            }
        }
        return false;
    }

    private void logIfIastScanForFirstTime(K2RequestIdentifier fuzzRequestIdentifier, HttpRequest request) {

        String url = StringUtils.EMPTY;
        if(request != null && StringUtils.isNotBlank(request.getUrl())) {
            url = request.getUrl();
        }

        if(StringUtils.isNotBlank(fuzzRequestIdentifier.getApiRecordId()) && !AgentUtils.getInstance().getScannedAPIIds().contains(fuzzRequestIdentifier.getApiRecordId())){
            AgentUtils.getInstance().getScannedAPIIds().add(fuzzRequestIdentifier.getApiRecordId());
            logger.log(LogLevel.INFO, String.format("IAST Scan for API %s with ID : %s started.", url, fuzzRequestIdentifier.getApiRecordId()), Agent.class.getName());
        }
    }

    private static boolean checkIfNRGeneratedEvent(AbstractOperation operation) {
        for (int i = 1, j = 0; i < operation.getStackTrace().length; i++) {
            // Only remove consecutive top com.newrelic and com.nr. elements from stack.
            if (i - 1 == j && StringUtils.startsWithAny(operation.getStackTrace()[i].getClassName(), "com.newrelic.", "com.nr.")) {
                j++;
            } else if (StringUtils.startsWithAny(operation.getStackTrace()[i].getClassName(), "com.newrelic.", "com.nr.")) {
                return true;
            }
        }
        return false;
    }

    private static boolean needToGenerateEvent(String apiID) {
        return !(getInstance().getCurrentPolicy().getProtectionMode().getEnabled()
                && getInstance().getCurrentPolicy().getProtectionMode().getApiBlocking().getEnabled()
                && AgentUtils.getInstance().getAgentPolicyParameters().getAllowedApis().contains(apiID)
        );
    }

    private UserClassEntity setUserClassEntity(AbstractOperation operation, SecurityMetaData securityMetaData) {
        UserClassEntity userClassEntity = new UserClassEntity();
        StackTraceElement userStackTraceElement = null;
        if(securityMetaData.getMetaData().getServiceTrace() != null && securityMetaData.getMetaData().getServiceTrace().length > 0){
            userStackTraceElement = securityMetaData.getMetaData().getServiceTrace()[0];
        }

        for (int i = 0; i < operation.getStackTrace().length; i++) {
            StackTraceElement stackTraceElement = operation.getStackTrace()[i];

            // Section for user class identification using API handlers
            if( !securityMetaData.getMetaData().isFoundAnnotedUserLevelServiceMethod() && URLMappingsHelper.getHandlersHash().contains(stackTraceElement.getClassName().hashCode())){
                //Found -> assign user class and return
                userClassEntity.setUserClassElement(stackTraceElement);
                securityMetaData.getMetaData().setUserLevelServiceMethodEncountered(true);
                userClassEntity.setCalledByUserCode(true);
                return userClassEntity;
            }

            //Fallback to old mechanism
            if(userStackTraceElement != null){
                if(StringUtils.equals(stackTraceElement.getClassName(), userStackTraceElement.getClassName())
                        && StringUtils.equals(stackTraceElement.getMethodName(), userStackTraceElement.getMethodName())){
                    userClassEntity.setUserClassElement(stackTraceElement);
                    userClassEntity.setCalledByUserCode(securityMetaData.getMetaData().isUserLevelServiceMethodEncountered());
                    userStackTraceElement = stackTraceElement;
                }
            }
            // TODO: the `if` should be `else if` please check crypto case BenchmarkTest01978. service trace is being registered from doSomething()
            if( i+1 < operation.getStackTrace().length && StringUtils.equals(operation.getSourceMethod(), stackTraceElement.toString())){
                userClassEntity.setUserClassElement(operation.getStackTrace()[i + 1]);
                userClassEntity.setCalledByUserCode(securityMetaData.getMetaData().isUserLevelServiceMethodEncountered());
            }
        }

        if(userClassEntity.getUserClassElement() == null && operation.getStackTrace().length >= 2){
            userClassEntity.setUserClassElement(operation.getStackTrace()[1]);
            userClassEntity.setCalledByUserCode(securityMetaData.getMetaData().isUserLevelServiceMethodEncountered());
        }
        return userClassEntity;
    }

    private void setRequiredStackTrace(AbstractOperation operation, SecurityMetaData securityMetaData) {
        StackTraceElement[] currentStackTrace = operation.getStackTrace();
        if (securityMetaData.getMetaData().getServiceTrace() != null && securityMetaData.getMetaData().getServiceTrace().length + 3 < currentStackTrace.length) {
            int targetBottomStackLength = currentStackTrace.length - securityMetaData.getMetaData().getServiceTrace().length + 3;
            currentStackTrace = Arrays.copyOfRange(currentStackTrace, 0, targetBottomStackLength);
            operation.setStackTrace(currentStackTrace);
        }
    }

    private static void processStackTrace(AbstractOperation operation) {
        StackTraceElement[] stackTrace = operation.getStackTrace();
        int resetFactor = 0;

        ArrayList<Integer> newTraceForIdCalc = new ArrayList<>(stackTrace.length);

        boolean markedForRemoval;
        for (int i = 0, j = -1; i < stackTrace.length; i++) {
            markedForRemoval = false;

            // Only remove consecutive top com.newrelic and com.nr. elements from stack.
            if (i - 1 == j && StringUtils.startsWithAny(stackTrace[i].getClassName(), "com.newrelic.agent.security.", "com.newrelic.api.agent.")) {
                resetFactor++;
                j++;
                markedForRemoval = true;
            }

            if (StringUtils.startsWithAny(stackTrace[i].getClassName(), SUN_REFLECT, COM_SUN)
                    || stackTrace[i].isNativeMethod() || stackTrace[i].getLineNumber() < 0 ||
                    !StringUtils.endsWith(stackTrace[i].getFileName(), ".java")) {
                markedForRemoval = true;

                // Checks for RCI flagging.
                if (NewRelic.getAgent().getConfig()
                        .getValue(INRSettingsKey.SECURITY_DETECTION_RCI_ENABLED, true) && i > 0) {
                    AgentMetaData metaData = NewRelicSecurity.getAgent().getSecurityMetaData().getMetaData();
                    if (stackTrace[i - 1].getLineNumber() > 0 &&
                            StringUtils.isNotBlank(stackTrace[i - 1].getFileName()) &&
                            !StringUtils.startsWithAny(stackTrace[i - 1].getClassName(), "com.newrelic.", "com.nr.")
                    ) {
                        metaData.setTriggerViaRCI(true);
                        metaData.getRciMethodsCalls()
                                .add(AgentUtils.stackTraceElementToString(operation.getStackTrace()[i]));
                        metaData.getRciMethodsCalls()
                                .add(AgentUtils.stackTraceElementToString(operation.getStackTrace()[i - 1]));
                    }
                }
            }

            if (!markedForRemoval) {
                newTraceForIdCalc.add(stackTrace[i].hashCode());
            }
        }
        stackTrace = Arrays.copyOfRange(stackTrace, resetFactor, stackTrace.length);
        operation.setStackTrace(stackTrace);
        operation.setSourceMethod(operation.getStackTrace()[0].toString());
        setAPIId(operation, newTraceForIdCalc, operation.getCaseType());
    }

    private static void setAPIId(AbstractOperation operation, List<Integer> traceForIdCalc, VulnerabilityCaseType vulnerabilityCaseType) {
        try {
            traceForIdCalc.add(operation.getSourceMethod().hashCode());
            int[] traceArray = new int[traceForIdCalc.size()];
            for (int i = 0; i < traceForIdCalc.size(); i++) {
                traceArray[i] = traceForIdCalc.get(i);
            }
            operation.setApiID(vulnerabilityCaseType.getCaseType() + "-" + HashGenerator.getXxHash64Digest(traceArray));
        } catch (IOException e) {
            operation.setApiID("UNDEFINED");
        }
    }

    @Override
    public void registerExitEvent(AbstractOperation operation) {
        if (operation == null) {
            return;
        }
        K2RequestIdentifier k2RequestIdentifier = NewRelicSecurity.getAgent().getSecurityMetaData().getFuzzRequestIdentifier();
        HttpRequest request = NewRelicSecurity.getAgent().getSecurityMetaData().getRequest();

        // TODO: Generate for only native payloads
        if (!request.isEmpty() && !operation.isEmpty() && k2RequestIdentifier.getK2Request()) {
            if (StringUtils.equals(k2RequestIdentifier.getApiRecordId(), operation.getApiID())
                    && StringUtils.equals(k2RequestIdentifier.getNextStage().getStatus(), IAgentConstants.VULNERABLE)) {
                ExitEventBean exitEventBean = new ExitEventBean(operation.getExecutionId(), operation.getCaseType().getCaseType());
                exitEventBean.setK2RequestIdentifier(k2RequestIdentifier.getRaw());
                logger.log(LogLevel.FINER, "Exit event : " + exitEventBean, this.getClass().getName());
                DispatcherPool.getInstance().dispatchExitEvent(exitEventBean);
                AgentInfo.getInstance().getJaHealthCheck().incrementExitEventSentCount();
            }
        }
    }

    @Override
    public boolean isSecurityActive() {
        if(isInitialised() && info != null){
            return info.isAgentActive();
        }
        return false;
    }

    @Override
    public AgentPolicy getCurrentPolicy() {
        return AgentUtils.getInstance().getAgentPolicy();
    }

    @Override
    public SecurityMetaData getSecurityMetaData() {
        if(!isSecurityActive()){
            return null;
        }
        try {
            Transaction tx = NewRelic.getAgent().getTransaction();
            if (tx != null) {
                Object meta = tx.getSecurityMetaData();
                if (meta instanceof SecurityMetaData) {
                    return (SecurityMetaData) meta;
                }
            }
        } catch (Throwable ignored) {}
        return new SecurityMetaData();
    }

    @Override
    public String getAgentUUID() {
        if (isInitialised() && info != null) {
            return this.info.getApplicationUUID();
        }
        return StringUtils.EMPTY;
    }

    @Override
    public String getAgentTempDir() {
        if (isInitialised() && info != null) {
            return OsVariablesInstance.getInstance().getOsVariables().getTmpDirectory();
        }
        return StringUtils.EMPTY;
    }

    public AgentInfo getInfo() {
        return info;
    }

    public AgentConfig getConfig() {
        return config;
    }

    public static java.net.URL getAgentJarURL() {
        return InstanceHolder.instance.agentJarURL;
    }

    public boolean isInitialised() {
        return isInitialised;
    }

    public void setInitialised(boolean initialised) {
        isInitialised = initialised;
    }

    @Override
    public Instrumentation getInstrumentation() {
        return this.instrumentation;
    }

    @Override
    public boolean isLowPriorityInstrumentationEnabled() {
        return NewRelicSecurity.isHookProcessingActive() && NewRelic.getAgent().getConfig().getValue(LowSeverityHelper.LOW_SEVERITY_HOOKS_ENABLED, LowSeverityHelper.DEFAULT);
    }

    public void setApplicationConnectionConfig(int port, String scheme) {
        AppServerInfo appServerInfo = AppServerInfoHelper.getAppServerInfo();
        appServerInfo.getConnectionConfiguration().put(port, scheme);
//        verifyConnectionAndPut(port, scheme, appServerInfo);
    }

    private void verifyConnectionAndPut(int port, String scheme, AppServerInfo appServerInfo) {
        if(isConnectionSuccessful(port, scheme)){
            appServerInfo.getConnectionConfiguration().put(port, scheme);
        } else if (isConnectionSuccessful(port,StringUtils.equalsAnyIgnoreCase(scheme, HTTPS_STR)? HTTP_STR : HTTPS_STR)) {
            appServerInfo.getConnectionConfiguration().put(port, StringUtils.equalsAnyIgnoreCase(scheme, HTTPS_STR)? HTTP_STR : HTTPS_STR);
        }
    }

    private boolean isConnectionSuccessful(int port, String scheme) {
        try {
            java.net.URL endpoint = new URL(String.format("%s://localhost:%s", scheme, port));
            HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection();

            // Set the request method to HEAD (you won't download the whole content)
            connection.setRequestMethod("HEAD");

            int responseCode = connection.getResponseCode();

            if (responseCode == HttpURLConnection.HTTP_OK) {
                return true;
            } else if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
                return true;
            } else {
                return false;
            }
        } catch (IOException e) {
            return false;
        }
    }

    public String getApplicationConnectionConfig(int port) {
        AppServerInfo appServerInfo = AppServerInfoHelper.getAppServerInfo();
        return appServerInfo.getConnectionConfiguration().get(port);
    }

    @Override
    public Map<Integer, String> getApplicationConnectionConfig() {
        return AppServerInfoHelper.getAppServerInfo().getConnectionConfiguration();
    }

    @Override
    public void setServerInfo(String key, String value) {
        AppServerInfo appServerInfo = AppServerInfoHelper.getAppServerInfo();
        switch (key) {
            case IUtilConstants.APPLICATION_DIRECTORY:
                File appBase = new File(value);
                if(appBase.isAbsolute()){
                    appServerInfo.setApplicationDirectory(value);
                } else if(StringUtils.isNotBlank(appServerInfo.getServerBaseDirectory())) {
                    appServerInfo.setApplicationDirectory(new File(appServerInfo.getServerBaseDirectory(), value).getAbsolutePath());
                } else if(appBase.isDirectory()) {
                    appServerInfo.setApplicationDirectory(appBase.getAbsolutePath());
                }
                break;
            case IUtilConstants.SERVER_BASE_DIRECTORY:
                appServerInfo.setServerBaseDirectory(value);
                break;
            case IUtilConstants.SAME_SITE_COOKIES:
                appServerInfo.setSameSiteCookies(value);
                break;
            case IUtilConstants.APPLICATION_TMP_DIRECTORY:
                appServerInfo.setApplicationTmpDirectory(value);
            default:
                break;
        }

    }

    @Override
    public String getServerInfo(String key) {
        AppServerInfo appServerInfo = AppServerInfoHelper.getAppServerInfo();
        switch (key) {
            case IUtilConstants.APPLICATION_DIRECTORY:
                return appServerInfo.getApplicationDirectory();
            case IUtilConstants.SERVER_BASE_DIRECTORY:
                return appServerInfo.getServerBaseDirectory();
            case IUtilConstants.SAME_SITE_COOKIES:
                return appServerInfo.getSameSiteCookies();
            case IUtilConstants.APPLICATION_TMP_DIRECTORY:
                return appServerInfo.getApplicationTmpDirectory();
            default:
                return null;
        }
    }

    @Override
    public void log(LogLevel logLevel, String event, Throwable throwableEvent, String logSourceClassName) {
        if(logger != null){
            logger.log(logLevel, event, throwableEvent, logSourceClassName);
        }
    }

    @Override
    public void log(LogLevel logLevel, String event, String logSourceClassName) {
        if(logger != null){
            logger.log(logLevel, event, logSourceClassName);
        }
    }

    @Override
    public void reportIncident(LogLevel logLevel, String event, Throwable exception, String caller) {
        if(logger != null){
            logger.postLogMessageIfNecessary(logLevel, event, exception, caller);
        }
    }

    @Override
    public void retransformUninstrumentedClass(Class<?> classToRetransform) {
        if (!classToRetransform.isAnnotationPresent(InstrumentedClass.class)) {
            try {
                getInstrumentation().retransformClasses(classToRetransform);
            } catch (UnmodifiableClassException e) {
                NewRelic.getAgent().getLogger().log(Level.FINE, "Unable to retransform class ", classToRetransform, " : ", e.getMessage());
            }
        } else {
            NewRelic.getAgent().getLogger().log(Level.FINER, "Class ", classToRetransform, " already instrumented.");
        }
    }

    @Override
    public String decryptAndVerify(String encryptedData, String hashVerifier) {
        String decryptedData = EncryptorUtils.decrypt(AgentInfo.getInstance().getLinkingMetadata().get(INRSettingsKey.NR_ENTITY_GUID), encryptedData);
        if(EncryptorUtils.verifyHashData(hashVerifier, decryptedData)) {
            return decryptedData;
        } else {
            NewRelic.getAgent().getLogger().log(Level.WARNING, String.format("Agent data decryption verifier fails on data : %s hash : %s", encryptedData, hashVerifier), Agent.class.getName());
            return null;
        }
    }
}