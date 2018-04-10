package io.thundra.lambda.warmup.strategy.impl;

import com.amazonaws.services.lambda.model.InvokeResult;
import com.amazonaws.util.StringUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsgenie.core.util.ExceptionUtil;
import com.opsgenie.sirocco.api.control.ControlRequestBuilder;
import com.opsgenie.sirocco.api.control.ControlRequestConstants;
import io.thundra.lambda.warmup.WarmupFunctionInfo;
import io.thundra.lambda.warmup.WarmupHandler;
import io.thundra.lambda.warmup.WarmupPropertyProvider;
import io.thundra.lambda.warmup.strategy.WarmupStrategy;
import org.json.JSONObject;

import java.io.IOException;
import java.text.ParseException;
import java.util.*;

/**
 * <p>
 *      {@link WarmupStrategy} implementation which
 *      takes Lambda stats into consideration while warming-up.
 *      If the target Lambda function is hot (invoked frequently),
 *      it is aimed to keep more instance of that Lambda function up
 *      by warmup it with more concurrent invocation.
 *      Name of this strategy is <code>stat-aware</code> ({@link #NAME}.
 * </p>
 * <p>
 *      This strategy invokes with warmup message in <code>#warmup wait=&lt;wait_time&gt;</code> format.
 *      In here <code>&lt;wait_time&gt;</code> is the additional delay time for the invoked target Lambda functions
 *      to wait before return. In here the strategy itself calculates <code>&lt;wait_time&gt;</code>
 *      by adding <b>extra</b> <code>100 milliseconds</code> for every <b>extra</b>
 *      <code>10</code> concurrent warmup invocation count. So, it suggested to wait
 *      <code>100 + &lt;wait_time&gt; milliseconds</code> for warmup requests at the target Lambda function side.
 * </p>
 * <p>
 *      As mentioned above, this strategy is smart enough to scale up/down warmup invocation counts
 *      according to target Lambda function usage stats. By this feature, hot functions are invoked
 *      with higher concurrent warmup invocation count by automatically increasing invocation count
 *      from standard/defined invocation count. The opposite logic is valid of cold functions
 *      by warming-up them lower concurrent warmup invocation count by automatically decreasing
 *      invocation count. To take advantage of this feature (note that this feature is optional,
 *      so in case of empty/null return value, auto scale feature is not used and goes on with standard
 *      invocation count as in {@link StandardWarmupStrategy}), target Lambda function should return an
 *      <code>instanceId</code> unique to the Lambda handler instance (ex. a random generated UUID for
 *      the Lambda handler instance) and <code>latestRequestTime</code>
 *      which represents the latest request (not empty/warmup message) time in
 *      <code>`yyyy-MM-dd HH:mm:ss.SSS`</code> format as JSON like below:
 * </p>
 * <pre> {@code
 * {
 * "instanceId": "9b3ba0d0-d515-4a21-b3ee-133a321d9dbe",
 * "latestRequestTime": "2017-07-30 17:26:27.778"
 * }
 * }</pre>
 *
 * @author serkan
 */
public class StatAwareWarmupStrategy extends StandardWarmupStrategy {

    /**
     * Name of the {@link StatAwareWarmupStrategy}.
     */
    public static final String NAME = "stat-aware";

    /**
     * Name of the <code>long</code> typed property
     * which configures the passed time in milliseconds to
     * consider a Lambda function is idle.
     */
    public static final String FUNCTION_INSTANCE_IDLE_TIME_PROP_NAME =
            "thundra.lambda.warmup.functionInstanceIdleTime";
    /**
     * Default value for {@link #FUNCTION_INSTANCE_IDLE_TIME_PROP_NAME} property.
     * The default value is <code>30 minutes</code>.
     */
    public static final long DEFAULT_FUNCTION_INSTANCE_IDLE_TIME = 30 * 60 * 1000; // 30 min

    /**
     * Name of the <code>float</code> typed property
     * which configures scale factor to increase/decrease
     * Lambda invocation count according to its stat (it is hot or not).
     */
    public static final String WARMUP_SCALE_FACTOR_PROP_NAME =
            "thundra.lambda.warmup.warmupScaleFactor";
    /**
     * Default value for {@link #WARMUP_SCALE_FACTOR_PROP_NAME} property.
     * The default value is <code>2.0</code>.
     */
    public static final float DEFAULT_WARMUP_SCALE_FACTOR = 2.0F;

    /**
     * Name of the <code>boolean</code> typed property
     * which disables warmup scale behaviour which is enabled by default
     * and scale factor is configured by {@link #WARMUP_SCALE_FACTOR_PROP_NAME} property.
     */
    public static final String DISABLE_WARMUP_SCALE_PROP_NAME =
            "thundra.lambda.warmup.disableWarmupScale";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, Map<String, Date>> functionLatestRequestTimeMap =
            new HashMap<String, Map<String, Date>>();
    private final long functionInstanceIdleTime;
    private final float warmupScaleFactor;
    private final boolean disableWarmupScale;

    public StatAwareWarmupStrategy() {
        this(WarmupHandler.DEFAULT_WARMUP_PROPERTY_PROVIDER);
    }

    public StatAwareWarmupStrategy(WarmupPropertyProvider warmupPropertyProvider) {
        this.functionInstanceIdleTime =
                warmupPropertyProvider.getLong(
                        FUNCTION_INSTANCE_IDLE_TIME_PROP_NAME,
                        DEFAULT_FUNCTION_INSTANCE_IDLE_TIME);
        this.warmupScaleFactor =
                warmupPropertyProvider.getFloat(
                        WARMUP_SCALE_FACTOR_PROP_NAME,
                        DEFAULT_WARMUP_SCALE_FACTOR);
        this.disableWarmupScale =
                warmupPropertyProvider.getBoolean(DISABLE_WARMUP_SCALE_PROP_NAME);
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    protected InvocationContext createInvocationContext(WarmupFunctionInfo functionInfo, String functionToBeWarmup,
                                                        String alias, int actualInvocationCount) {
        return new StatAwareInvocationContext(functionInfo, functionToBeWarmup, alias, actualInvocationCount);
    }

    @Override
    protected byte[] createInvokeRequestPayload(InvocationContext invocationContext, int invocationNo) {
        int delay = 100 * (invocationContext.actualInvocationCount / 10); // Additional wait time to default one (100 ms)
        StatAwareInvocationContext statAwareInvocationContext = (StatAwareInvocationContext) invocationContext;
        if (statAwareInvocationContext.longWarmupInvocationNo == invocationNo) {
            delay = delay * 10;
        }
        String controlRequest =
                new ControlRequestBuilder().
                            controlRequestType("warmup").
                            controlRequestArgument(ControlRequestConstants.WAIT_ARGUMENT, delay).
                        build();
        return controlRequest.getBytes();
    }

    private boolean isFunctionInstanceExpired(long currentTime, long latestRequestTime) {
        return currentTime > latestRequestTime + functionInstanceIdleTime;
    }

    @Override
    protected int getInvocationCount(String functionName, int defaultInvocationCount, int configuredInvocationCount,
                                     WarmupFunctionInfo functionInfo) {
        int invocationCount;
        if (disableWarmupScale) {
            invocationCount =
                    super.getInvocationCount(functionName, defaultInvocationCount, configuredInvocationCount, functionInfo);
            logger.info(
                    "Calculated invocation count in standard way for function " + functionName + ": " + invocationCount);
        } else {
            Map<String, Date> latestRequestTimeMap = functionLatestRequestTimeMap.get(functionName);
            if (latestRequestTimeMap != null) {
                long currentTime = System.currentTimeMillis();
                int activeInstanceCount = 0;
                Iterator<Date> iter = latestRequestTimeMap.values().iterator();
                while (iter.hasNext()) {
                    Long latestRequestTime = iter.next().getTime();
                    if (isFunctionInstanceExpired(currentTime, latestRequestTime)) {
                        iter.remove();
                    } else {
                        activeInstanceCount++;
                    }
                }
                logger.info("Detected active instance count for function " + functionName + ": " + activeInstanceCount);
                invocationCount = Math.max((int) (activeInstanceCount * warmupScaleFactor), 1);

            } else {
                invocationCount =
                        super.getInvocationCount(functionName, defaultInvocationCount, configuredInvocationCount, functionInfo);
            }
            logger.info(
                    "Calculated invocation count by taking warmup scale factor into consideration " +
                    "for function " + functionName + ": " + invocationCount);
        }
        return invocationCount;
    }

    @Override
    protected void handleInvokeResultInfos(Map<String, List<InvokeResultInfo>> invokeResultInfosMap) {
        for (Map.Entry<String, List<InvokeResultInfo>> entry : invokeResultInfosMap.entrySet()) {
            String functionName = entry.getKey();
            List<InvokeResultInfo> invokeResultInfos = entry.getValue();
            for (InvokeResultInfo invokeResultInfo : invokeResultInfos) {
                InvokeResult invokeResult = invokeResultInfo.invokeResult;
                String functionError = invokeResult.getFunctionError();
                if (StringUtils.hasValue(functionError)) {
                    JSONObject invokeResultJsonObj =
                            new JSONObject(new String(invokeResult.getPayload().array()));
                    String errorMessage;
                    if (invokeResultJsonObj.has("errorMessage")) {
                        errorMessage = invokeResultJsonObj.getString("errorMessage");
                    } else {
                        errorMessage = functionError;
                    }
                    logger.error("Warmup invocation for function " + functionName +
                                 " has returned with error: " + errorMessage);
                } else {
                    String response = new String(invokeResult.getPayload().array());
                    if (StringUtils.isNullOrEmpty(response)) {
                        continue;
                    }
                    Map<String, Object> responseValues = null;
                    try {
                        responseValues = objectMapper.readValue(response, Map.class);
                    } catch (IOException e) {
                        ExceptionUtil.sneakyThrow(e);
                    }
                    if (responseValues == null) {
                        continue;
                    }
                    String instanceId = (String) responseValues.get("instanceId");
                    String latestRequestTimeStr = (String) responseValues.get("latestRequestTime");
                    if (latestRequestTimeStr != null) {
                        Date latestRequestTime = null;
                        try {
                            latestRequestTime = ControlRequestConstants.DATE_FORMAT.parse(latestRequestTimeStr);
                        } catch (ParseException e) {
                            ExceptionUtil.sneakyThrow(e);
                        }
                        if (latestRequestTime.getTime() > 0) {
                            Map<String, Date> latestRequestTimeMap = functionLatestRequestTimeMap.get(functionName);
                            if (latestRequestTimeMap == null) {
                                latestRequestTimeMap = new HashMap<String, Date>();
                                functionLatestRequestTimeMap.put(functionName, latestRequestTimeMap);
                            }
                            latestRequestTimeMap.put(instanceId, latestRequestTime);
                        }
                    }
                }
            }
        }

        logger.info("Latest requests times of functions: " + functionLatestRequestTimeMap);

        evictExpiredLatestRequestTimes();
    }

    private void evictExpiredLatestRequestTimes() {
        long currentTime = System.currentTimeMillis();
        for (Map.Entry<String, Map<String, Date>> entry : functionLatestRequestTimeMap.entrySet()) {
            Map<String, Date> latestRequestTimeMap = entry.getValue();
            Iterator<Date> iter = latestRequestTimeMap.values().iterator();
            while (iter.hasNext()) {
                Long latestRequestTime = iter.next().getTime();
                if (isFunctionInstanceExpired(currentTime, latestRequestTime)) {
                    iter.remove();
                }
            }
        }
    }

    private static class StatAwareInvocationContext extends InvocationContext {

        private static final Random RANDOM = new Random();

        private final int longWarmupInvocationNo;

        public StatAwareInvocationContext(WarmupFunctionInfo functionInfo, String functionToBeWarmup,
                                          String alias, int actualInvocationCount) {
            super(functionInfo, functionToBeWarmup, alias, actualInvocationCount);
            this.longWarmupInvocationNo = 1 + RANDOM.nextInt(actualInvocationCount);
        }

    }

}
