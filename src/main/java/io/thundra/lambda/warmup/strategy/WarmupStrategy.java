package io.thundra.lambda.warmup.strategy;

import com.amazonaws.services.lambda.runtime.Context;
import io.thundra.lambda.warmup.LambdaService;
import io.thundra.lambda.warmup.WarmupFunctionInfo;

import java.io.IOException;
import java.util.Map;

/**
 * Interface for implementations which execute warmup action for the given AWS Lambda functions.
 *
 * @author serkan
 */
public interface WarmupStrategy {

    /**
     * Gets the unique name of this strategy.
     *
     * @return the unique name of this strategy
     */
    String getName();

    /**
     * Executes warmup action
     *
     * @param context           the {@link Context Lambda context}
     * @param lambdaService     the {@link LambdaService Lambda service}
     *                          to be used for Lambda related operations
     * @param functionsToWarmup Lambda function to warmup
     *
     * @throws IOException if there is any I/O related exception
     */
    void warmup(Context context,
                LambdaService lambdaService,
                Map<String, WarmupFunctionInfo> functionsToWarmup) throws IOException;

}
