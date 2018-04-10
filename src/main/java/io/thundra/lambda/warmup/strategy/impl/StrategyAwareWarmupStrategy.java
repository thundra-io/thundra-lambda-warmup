package io.thundra.lambda.warmup.strategy.impl;

import com.amazonaws.services.lambda.runtime.Context;
import com.opsgenie.core.util.ExceptionUtil;
import io.thundra.lambda.warmup.LambdaService;
import io.thundra.lambda.warmup.WarmupFunctionInfo;
import io.thundra.lambda.warmup.strategy.WarmupStrategy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * {@link WarmupStrategy} implementation which
 * takes configured/specified {@link WarmupStrategy}s for functions into consideration
 * while warming-up. If there is no configured/specified {@link WarmupStrategy}s,
 * uses given {@link WarmupStrategy} by default.
 * Name of this strategy is <code>strategy-aware</code> ({@link #NAME}.
 *
 * @author serkan
 */
public class StrategyAwareWarmupStrategy implements WarmupStrategy {

    public static final String NAME = "strategy-aware";

    private final WarmupStrategy warmupStrategy;
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    public StrategyAwareWarmupStrategy(WarmupStrategy warmupStrategy) {
        this.warmupStrategy = warmupStrategy;
    }

    @Override
    public String getName() {
        return NAME;
    }

    public WarmupStrategy getWarmupStrategy() {
        return warmupStrategy;
    }

    @Override
    public void warmup(Context context,
                       LambdaService lambdaService,
                       Map<String, WarmupFunctionInfo> functionsToWarmup) throws IOException {
        Map<String, WarmupFunctionInfo> functionsToWarmupByDefault =
                new HashMap<String, WarmupFunctionInfo>();
        Map<WarmupStrategy, Map<String, WarmupFunctionInfo>> functionsToWarmupByStrategy =
                new HashMap<WarmupStrategy, Map<String, WarmupFunctionInfo>>();

        for (Map.Entry<String, WarmupFunctionInfo> entry : functionsToWarmup.entrySet()) {
            String functionName = entry.getKey();
            WarmupFunctionInfo functionInfo = entry.getValue();
            WarmupStrategy warmupStrategy = functionInfo.getWarmupStrategy();
            if (warmupStrategy == null) {
                functionsToWarmupByDefault.put(functionName, functionInfo);
            } else {
                if (warmupStrategy == this.warmupStrategy) {
                    functionsToWarmupByDefault.put(functionName, functionInfo);
                } else {
                    Map<String, WarmupFunctionInfo> warmupFunctionInfoMap =
                            functionsToWarmupByStrategy.get(warmupStrategy);
                    if (warmupFunctionInfoMap == null) {
                        warmupFunctionInfoMap = new HashMap<String, WarmupFunctionInfo>();
                        functionsToWarmupByStrategy.put(warmupStrategy, warmupFunctionInfoMap);
                    }
                    warmupFunctionInfoMap.put(functionName, functionInfo);
                }
            }
        }

        /*
         * Every delegated warmup strategy can be called only one thread at a time.
         * But they can be called from different threads.
         * So there is no need to monitor between them
         * but we need to ensure that there is visibility.
         * We don't take any action to guarantee visibility
         * because it is naturally provided by internals of "ThreadPoolExecutor".
         * "ThreadPoolExecutor" has "AtomicInteger" typed "ctl" field and
         * it is read before every task submission and written after every task completion.
         * Therefore there is happens-before relationship between the tasks
         * which executes same delegated warmup strategy.
         */

        List<Future> futures = new ArrayList<Future>();

        if (!functionsToWarmupByDefault.isEmpty()) {
            Future future =
                    executorService.submit(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                warmupStrategy.warmup(context, lambdaService, functionsToWarmupByDefault);
                            } catch (IOException e) {
                                ExceptionUtil.sneakyThrow(e);
                            }
                        }
                    });
            futures.add(future);
        }

        for (Map.Entry<WarmupStrategy, Map<String, WarmupFunctionInfo>> entry :
                functionsToWarmupByStrategy.entrySet()) {
            WarmupStrategy warmupStrategy = entry.getKey();
            Map<String, WarmupFunctionInfo> functionInfoMap = entry.getValue();
            Future future =
                    executorService.submit(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                warmupStrategy.warmup(context, lambdaService, functionInfoMap);
                            } catch (IOException e) {
                                ExceptionUtil.sneakyThrow(e);
                            }
                        }
                    });
            futures.add(future);
        }

        List<Throwable> errors = new ArrayList<Throwable>();
        for (int i = 0; i < futures.size(); i++) {
            try {
                Future future = futures.get(i);
                future.get();
            } catch (Throwable error) {
                if (error instanceof ExecutionException && error.getCause() != null) {
                    error = error.getCause();
                }
                errors.add(error);
            }
        }
        if (!errors.isEmpty()) {
            RuntimeException warmupException = new RuntimeException("Error occurred while warmup!");
            for (Throwable error : errors) {
                warmupException.addSuppressed(error);
            }
            throw warmupException;
        }
    }

}
