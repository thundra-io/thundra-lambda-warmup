package io.thundra.lambda.warmup.strategy.impl;

import com.amazonaws.services.lambda.model.InvokeRequest;
import com.amazonaws.services.lambda.model.InvokeResult;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.util.StringUtils;
import io.thundra.lambda.warmup.LambdaService;
import io.thundra.lambda.warmup.WarmupFunctionInfo;
import io.thundra.lambda.warmup.WarmupHandler;
import io.thundra.lambda.warmup.WarmupPropertyProvider;
import io.thundra.lambda.warmup.strategy.WarmupStrategy;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <p>
 *      Standard {@link WarmupStrategy} implementation which
 *      warmup incrementally as randomized invocation counts
 *      for preventing full load (all containers are busy with warmup invocations)
 *      on AWS Lambda during warmup to leave some AWS Lambda containers free/available
 *      for real requests and simulating real environment as much as possible.
 *      Name of this strategy is <code>standard</code> ({@link #NAME}.
 * </p>
 * <p>
 *      This strategy invokes with empty warmup messages if no invocation data is specified by
 *      <code>thundra.lambda.warmup.invocationData</code>. Therefore, the target Lambda functions to warmup
 *      must handle empty messages. By default it is suggested to wait <code>100 milliseconds</code>
 *      for warmup requests before return. This is needed for keeping multiple Lambda containers up.
 *      The reason is that when there is no delay, the invoked Lambda container does its job quickly
 *      and becomes available to be reused in a very short time. So it is expected that
 *      multiple warmup invocations are dispatched to the same Lambda container instead of another one.
 *      By waiting before return, warmup request keep Lambda container busy and therefore,
 *      possibly the other warmup requests are routed to another containers even create new one
 *      if there is no available one. If the concurrent warmup invocation count increases, wait time
 *      at target Lambda function side should be increased accordingly as well. Because delay time
 *      at target Lambda function side might be insufficient for the required time high number of
 *      concurrent warmup invocations to keep containers busy in the meantime.
 *      For every <code>10</code> concurrent invocation, <code>100 milliseconds</code> wait time
 *      is reasonable by our experiments.
 * </p>
 *
 * @author serkan
 */
public class StandardWarmupStrategy implements WarmupStrategy {

    public static final String NAME = "standard";

    /**
     * Name of the <code>integer</code> typed property
     * which configures the invocation count for each Lambda function to warmup.
     * Note that if invocation counts are randomized,
     * this value is used as upper limit of randomly generated invocation count.
     */
    public static final String INVOCATION_COUNT_PROP_NAME =
            "thundra.lambda.warmup.invocationCount";
    /**
     * Default value for {@link #INVOCATION_COUNT_PROP_NAME} property.
     * The default value is <code>8</code>.
     */
    public static final int DEFAULT_INVOCATION_COUNT = 8;

    /**
     * Name of the <code>integer</code> typed property
     * which configures the count of consumers
     * to get results of warmup invocations.
     */
    public static final String INVOCATION_RESULT_CONSUMER_COUNT_PROP_NAME =
            "thundra.lambda.warmup.invocationResultConsumerCount";
    /**
     * Default value for {@link #DEFAULT_INVOCATION_RESULT_CONSUMER_COUNT} property.
     * The default value is two times of available CPU processors.
     */
    public static final int DEFAULT_INVOCATION_RESULT_CONSUMER_COUNT =
            2 * Runtime.getRuntime().availableProcessors();

    /**
     * Name of the <code>integer</code> typed property
     * which configures the warmup iteration count.
     */
    public static final String ITERATION_COUNT_PROP_NAME =
            "thundra.lambda.warmup.iterationCount";
    /**
     * Default value for {@link #ITERATION_COUNT_PROP_NAME} property.
     * The default value is <code>2</code>.
     */
    public static final int DEFAULT_ITERATION_COUNT = 2;

    /**
     * Name of the <code>boolean</code> typed property
     * which enables splitting iterations between multiple schedules of this handler
     * and at each schedule call only one iteration is performed.
     */
    public static final String ENABLE_SPLIT_ITERATIONS_PROP_NAME =
            "thundra.lambda.warmup.enableSplitIterations";

    /**
     * Name of the <code>long</code> typed property
     * which configures the time interval in milliseconds
     * to bypass randomization and directly use invocation count.
     */
    public static final String RANDOMIZATION_BYPASS_INTERVAL_MILLIS_PROP_NAME =
            "thundra.lambda.warmup.randomizationBypassInterval";
    /**
     * Default value for {@link #RANDOMIZATION_BYPASS_INTERVAL_MILLIS_PROP_NAME} property.
     * The default value is <code>15 minutes</code>.
     */
    public static final long DEFAULT_RANDOMIZATION_TIMEOUT_MILLIS = 15 * 60 * 1000; // 15 min

    /**
     * Name of the <code>boolean</code> typed property
     * which disables randomized invocation count behaviour.
     * Note that invocations counts are randomized
     * for preventing full load (all containers are busy with warmup invocations)
     * on AWS Lambda during warmup to leave some AWS Lambda containers free/available
     * for real requests and simulating real environment as much as possible.
     */
    public static final String DISABLE_RANDOMIZATION_PROP_NAME =
            "thundra.lambda.warmup.disableRandomization";

    /**
     * Name of the <code>string</code> typed property
     * which configures alias to be used as qualifier
     * while invoking Lambda functions to warmup.
     */
    public static final String WARMUP_FUNCTION_ALIAS_PROP_NAME =
            "thundra.lambda.warmup.warmupFunctionAlias";

    /**
     * Name of the <code>boolean</code> typed property
     * which enables throwing error behaviour
     * if the warmup invocation fails for some reason.
     */
    public static final String THROW_ERROR_ON_FAILURE_PROP_NAME =
            "thundra.lambda.warmup.throwErrorOnFailure";

    /**
     * Name of the <code>boolean</code> typed property
     * which disables waiting behaviour between each warmup invocation round.
     */
    public static final String DONT_WAIT_BETWEEN_INVOCATION_ROUNDS =
            "thundra.lambda.warmup.dontWaitBetweenInvocationRounds";

    protected final Logger logger = Logger.getLogger(getClass());

    protected final int invocationCount;
    protected final int invocationResultConsumerCount;
    protected final int iterationCount;
    protected final boolean splitIterations;
    protected int currentIterationCount = 0;
    protected final long randomizationBypassIntervalMillis;
    protected final boolean disableRandomization;
    protected final String warmupFunctionAlias;
    protected final boolean throwErrorOnFailure;
    protected final boolean dontWaitBetweenInvocationRounds;

    protected final Map<String, Long> functionCallTimes = new HashMap<String, Long>();
    protected final ExecutorService executorService;
    protected final Random random = new Random();

    public StandardWarmupStrategy() {
        this(WarmupHandler.DEFAULT_WARMUP_PROPERTY_PROVIDER);
    }

    public StandardWarmupStrategy(WarmupPropertyProvider warmupPropertyProvider) {
        this.invocationCount =
                warmupPropertyProvider.getInteger(
                        INVOCATION_COUNT_PROP_NAME,
                        DEFAULT_INVOCATION_COUNT);
        this.invocationResultConsumerCount =
                warmupPropertyProvider.getInteger(
                        INVOCATION_RESULT_CONSUMER_COUNT_PROP_NAME,
                        DEFAULT_INVOCATION_RESULT_CONSUMER_COUNT);
        this.iterationCount =
                warmupPropertyProvider.getInteger(
                        ITERATION_COUNT_PROP_NAME,
                        DEFAULT_ITERATION_COUNT);
        this.splitIterations =
                warmupPropertyProvider.getBoolean(ENABLE_SPLIT_ITERATIONS_PROP_NAME);
        this.randomizationBypassIntervalMillis =
                warmupPropertyProvider.getLong(
                        RANDOMIZATION_BYPASS_INTERVAL_MILLIS_PROP_NAME,
                        DEFAULT_RANDOMIZATION_TIMEOUT_MILLIS);
        this.disableRandomization =
                warmupPropertyProvider.getBoolean(DISABLE_RANDOMIZATION_PROP_NAME);
        this.warmupFunctionAlias =
                warmupPropertyProvider.getString(WARMUP_FUNCTION_ALIAS_PROP_NAME);
        this.throwErrorOnFailure =
                warmupPropertyProvider.getBoolean(THROW_ERROR_ON_FAILURE_PROP_NAME);
        this.dontWaitBetweenInvocationRounds =
                warmupPropertyProvider.getBoolean(DONT_WAIT_BETWEEN_INVOCATION_ROUNDS);
        this.executorService =
                Executors.newFixedThreadPool(invocationResultConsumerCount);
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public void warmup(Context context,
                       LambdaService lambdaService,
                       Map<String, WarmupFunctionInfo> functionsToWarmup) throws IOException {
        int defaultInvocationCount = getDefaultInvocationCount();

        logger.info("Default invocation count per function: " + defaultInvocationCount);

        long remainingMillis = context.getRemainingTimeInMillis();
        long iterationDurationMillis = remainingMillis / iterationCount;
        int invocationCountPerIteration = defaultInvocationCount / iterationCount;
        int remainingInvocationCountAtFinalRound =
                defaultInvocationCount - (invocationCountPerIteration * iterationCount);

        logger.info("Iteration count: " + iterationCount);

        ///////////////////////////////////////////////////////////////////////////////

        AtomicLong invocationResultCounter = new AtomicLong(0L);
        LinkedBlockingQueue<InvokeResultInfo> invocationResultFutures = new LinkedBlockingQueue<>();
        List<InvokeResultError> errors = new CopyOnWriteArrayList<>();
        AtomicBoolean stopFlag = new AtomicBoolean(false);
        List<Future> futures = new ArrayList<>(invocationResultConsumerCount);

        try {
            for (int i = 0; i < invocationResultConsumerCount; i++) {
                InvocationResultConsumer invocationResultConsumer =
                        new InvocationResultConsumer(
                                invocationResultCounter,
                                invocationResultFutures,
                                errors,
                                stopFlag);
                Future future = executorService.submit(invocationResultConsumer);
                futures.add(future);
            }

            ///////////////////////////////////////////////////////////////////////////////

            Map<String, List<InvokeResultInfo>> invokeResultInfosMap = new HashMap<String, List<InvokeResultInfo>>();

            logger.info("Starting iterations to warmup ...");

            int invokeCount = (currentIterationCount + 1) * invocationCountPerIteration;
            for (int i = currentIterationCount; i < iterationCount; i++) {
                long startTime = System.currentTimeMillis();

                logger.info(String.format("Iteration round %d ...", (i + 1)));
                for (Map.Entry<String, WarmupFunctionInfo> entry : functionsToWarmup.entrySet()) {
                    String functionToBeWarmup = entry.getKey();
                    WarmupFunctionInfo functionInfo = entry.getValue();

                    if (i + 1 == iterationCount) {
                        invokeCount += remainingInvocationCountAtFinalRound;
                    }

                    int actualInvocationCount = invokeCount;
                    boolean randomize = !disableRandomization;
                    Long callTime = functionCallTimes.get(functionToBeWarmup);
                    if (    callTime == null
                            ||
                            (System.currentTimeMillis() - callTime) > randomizationBypassIntervalMillis) {
                        functionCallTimes.remove(functionToBeWarmup);
                        randomize = false;
                    }
                    if (randomize) {
                        actualInvocationCount =
                                calculateRandomizedInvocationCount(actualInvocationCount, invocationCountPerIteration);
                    }

                    int functionInvocationCount =
                            getInvocationCount(
                                    functionToBeWarmup,
                                    defaultInvocationCount,
                                    functionInfo.getInvocationCount(),
                                    functionInfo);
                    if (functionInvocationCount > 0) {
                        actualInvocationCount =
                                (int) (((double) (functionInvocationCount * actualInvocationCount)) / defaultInvocationCount);
                    }

                    if (actualInvocationCount == 0) {
                        actualInvocationCount = 1;
                    }

                    String alias = null;
                    if (StringUtils.hasValue(warmupFunctionAlias)) {
                        alias = warmupFunctionAlias;
                    }
                    if (StringUtils.hasValue(functionInfo.getAlias())) {
                        alias = functionInfo.getAlias();
                    }

                    if (alias != null) {
                        logger.info(String.format(
                                "Invoking function %s with alias '%s' to warmup for %d times ...",
                                functionToBeWarmup, alias, actualInvocationCount));
                    } else {
                        logger.info(String.format(
                                "Invoking function %s to warmup for %d times ...",
                                functionToBeWarmup, actualInvocationCount));
                    }

                    InvocationContext invocationContext =
                            createInvocationContext(functionInfo, functionToBeWarmup, alias, actualInvocationCount);
                    for (int j = 0; j < actualInvocationCount; j++) {
                        if (logger.isDebugEnabled()) {
                            logger.debug(String.format("Invocation round %d ...", (j + 1)));
                        }
                        InvokeRequest invokeRequest = createInvokeRequest(invocationContext, j + 1);
                        Future<InvokeResult> invokeResultFuture = lambdaService.invokeAsync(invokeRequest);
                        invocationResultCounter.incrementAndGet();
                        InvokeResultInfo invokeResultInfo =
                                new InvokeResultInfo(
                                        (i + 1), (j + 1),
                                        functionToBeWarmup, invokeResultFuture);
                        List<InvokeResultInfo> invokeResultInfos = invokeResultInfosMap.get(functionToBeWarmup);
                        if (invokeResultInfos == null) {
                            invokeResultInfos = new ArrayList<InvokeResultInfo>();
                            invokeResultInfosMap.put(functionToBeWarmup, invokeResultInfos);
                        }
                        invokeResultInfos.add(invokeResultInfo);
                        invocationResultFutures.offer(invokeResultInfo);
                    }

                    functionCallTimes.putIfAbsent(functionToBeWarmup, System.currentTimeMillis());
                }

                invokeCount += invocationCountPerIteration;
                invokeCount = Math.min(invokeCount, defaultInvocationCount);

                if (splitIterations) {
                    break;
                }

                // No need to sleep at last round
                if (i < iterationCount - 1 && !dontWaitBetweenInvocationRounds) {
                    long passedTime = System.currentTimeMillis() - startTime;
                    long iterationRemainingMillis = iterationDurationMillis - passedTime;
                    try {
                        logger.info(String.format(
                                "Sleeping %d millis for next iteration ...", iterationRemainingMillis));
                        Thread.sleep(iterationRemainingMillis);
                    } catch (InterruptedException e) {
                    }
                }
            }

            logger.info("Finished iterations to warmup");

            ///////////////////////////////////////////////////////////////////////////////

            logger.info("Started waiting for invocations results ...");

            try {
                // We don't wait by timeout but wait infinite on purpose.
                // Because while waiting, if there is a timeout for this warmup handler function,
                // we should be aware of it
                while (invocationResultCounter.get() > 0) {
                    Thread.sleep(1000);
                }
            } catch (InterruptedException e) {
            }

            stopFlag.set(true);

            Iterator<Future> iter = futures.iterator();
            while (iter.hasNext()) {
                Future future = iter.next();
                future.cancel(true);
                iter.remove();
            }

            ///////////////////////////////////////////////////////////////////////////////

            handleInvokeResultInfos(invokeResultInfosMap);

            if (!errors.isEmpty()) {
                handleErrors(errors);
            }

            logger.info("Finished waiting for invocations results");
        } finally {
            if (splitIterations) {
                currentIterationCount = (currentIterationCount + 1) % iterationCount;
            }
            for (Future future : futures) {
                future.cancel(true);
            }
        }
    }

    protected int calculateRandomizedInvocationCount(int actualInvocationCount, int invocationCountPerIteration) {
        return  actualInvocationCount
                -
                (random.nextInt(invocationCountPerIteration / 2));
    }

    protected int getDefaultInvocationCount() {
        return invocationCount;
    }

    protected int getInvocationCount(String functionName, int defaultInvocationCount, int configuredInvocationCount,
                                     WarmupFunctionInfo functionInfo) {
        if (configuredInvocationCount > 0) {
            return configuredInvocationCount;
        }
        return defaultInvocationCount;
    }

    protected InvocationContext createInvocationContext(WarmupFunctionInfo functionInfo, String functionToBeWarmup,
                                                        String alias, int actualInvocationCount) {
        return new InvocationContext(functionInfo, functionToBeWarmup, alias, actualInvocationCount);
    }

    protected InvokeRequest createInvokeRequest(InvocationContext invocationContext, int invocationNo) {
        InvokeRequest invokeRequest =
            new InvokeRequest().
                    withFunctionName(invocationContext.functionToBeWarmup).
                    withPayload(ByteBuffer.wrap(createInvokeRequestPayload(invocationContext, invocationNo)));
        if (invocationContext.alias != null) {
            invokeRequest.withQualifier(invocationContext.alias);
        }
        return invokeRequest;
    }

    protected byte[] createInvokeRequestPayload(InvocationContext invocationContext, int invocationNo) {
        String invocationData = invocationContext.functionInfo.getInvocationData();
        if (StringUtils.isNullOrEmpty(invocationData)) {
            return new byte[0];
        } else {
            return invocationData.getBytes();
        }
    }

    protected void handleInvokeResultInfos(Map<String, List<InvokeResultInfo>> invokeResultInfosMap) {
    }

    protected void handleErrors(List<InvokeResultError> errors) {
        StringBuilder errorMessageBuilder = new StringBuilder("[ERRORS]\n");
        int errorCount = 1;
        for (InvokeResultError error : errors) {
            errorMessageBuilder.append("\t- Error [").append(errorCount++).append("]\n");
            errorMessageBuilder.append("\t\t- Iteration  No: ").append(error.iterationNo).append("\n");
            errorMessageBuilder.append("\t\t- Invocation No: ").append(error.invocationNo).append("\n");
            errorMessageBuilder.append("\t\t- Function Name: ").append(error.functionName).append("\n");
            errorMessageBuilder.append("\t\t- Error        : ").append(error.error.getMessage()).append("\n");
        }
        if (throwErrorOnFailure) {
            logger.error(errorMessageBuilder.toString());
        } else {
            throw new RuntimeException(errorMessageBuilder.toString());
        }
    }

    protected static class InvocationContext {

        protected final WarmupFunctionInfo functionInfo;
        protected final String functionToBeWarmup;
        protected final String alias;
        protected final int actualInvocationCount;

        public InvocationContext(WarmupFunctionInfo functionInfo, String functionToBeWarmup,
                                 String alias, int actualInvocationCount) {
            this.functionInfo = functionInfo;
            this.functionToBeWarmup = functionToBeWarmup;
            this.alias = alias;
            this.actualInvocationCount = actualInvocationCount;
        }

        public WarmupFunctionInfo getFunctionInfo() {
            return functionInfo;
        }

        public String getFunctionToBeWarmup() {
            return functionToBeWarmup;
        }

        public String getAlias() {
            return alias;
        }

        public int getActualInvocationCount() {
            return actualInvocationCount;
        }

    }

    protected static class InvokeResultInfo {

        protected final int iterationNo;
        protected final int invocationNo;
        protected final String functionName;
        protected final Future<InvokeResult> invokeResultFuture;
        protected volatile InvokeResult invokeResult;

        protected InvokeResultInfo(int iterationNo, int invocationNo,
                                   String functionName, Future<InvokeResult> invokeResultFuture) {
            this.iterationNo = iterationNo;
            this.invocationNo = invocationNo;
            this.functionName = functionName;
            this.invokeResultFuture = invokeResultFuture;
        }

    }

    protected static class InvokeResultError {

        protected final int iterationNo;
        protected final int invocationNo;
        protected final String functionName;
        protected final Throwable error;

        protected InvokeResultError(int iterationNo, int invocationNo,
                                    String functionName, Throwable error) {
            this.iterationNo = iterationNo;
            this.invocationNo = invocationNo;
            this.functionName = functionName;
            this.error = error;
        }

    }

    protected class InvocationResultConsumer implements Runnable {

        protected final AtomicLong invocationResultCounter;
        protected final LinkedBlockingQueue<InvokeResultInfo> invocationResultFutures;
        protected final List<InvokeResultError> errors;
        protected final AtomicBoolean stopFlag;

        protected InvocationResultConsumer(AtomicLong invocationResultCounter,
                                           LinkedBlockingQueue<InvokeResultInfo> invocationResultFutures,
                                           List<InvokeResultError> errors,
                                           AtomicBoolean stopFlag) {
            this.invocationResultCounter = invocationResultCounter;
            this.invocationResultFutures = invocationResultFutures;
            this.errors = errors;
            this.stopFlag = stopFlag;
        }

        @Override
        public void run() {
            while (!stopFlag.get()) {
                InvokeResultInfo invokeResultInfo = null;
                try {
                    invokeResultInfo = invocationResultFutures.take();
                    invokeResultInfo.invokeResult = invokeResultInfo.invokeResultFuture.get();
                    if (logger.isDebugEnabled()) {
                        logger.debug(String.format(
                                "Invocation result has been successfully retrieved at iteration %d and invocation %d for function %s",
                                invokeResultInfo.iterationNo, invokeResultInfo.invocationNo, invokeResultInfo.functionName));
                    }
                } catch (Throwable t) {
                    if (t instanceof InterruptedException) {
                        return;
                    }
                    if (invokeResultInfo != null) {
                        logger.error(String.format(
                                "Retrieving invocation result has failed at iteration %d and invocation %d for function %s!",
                                invokeResultInfo.iterationNo, invokeResultInfo.invocationNo, invokeResultInfo.functionName),
                                t);
                        errors.add(new InvokeResultError(
                                invokeResultInfo.iterationNo, invokeResultInfo.invocationNo,
                                invokeResultInfo.functionName, t));
                    } else {
                        logger.error("Error occurred while retrieving invocation result!", t);
                    }
                } finally {
                    invocationResultCounter.decrementAndGet();
                }
            }
        }

    }

}
