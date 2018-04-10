package io.thundra.lambda.warmup;

import com.amazonaws.services.lambda.model.*;

import java.util.concurrent.Future;

/**
 * Interface for implementations which provide various services for AWS Lambda
 * to be used for warmup.
 *
 * @author serkan
 */
public interface LambdaService {

    /**
     * Invokes Lambda function synchronously.
     *
     * @param request the {@link InvokeRequest invocation request}
     * @return the {@link InvokeResult invocation result}
     */
    InvokeResult invoke(InvokeRequest request);

    /**
     * Invokes Lambda function asynchronously.
     *
     * @param request the {@link InvokeRequest invocation request}
     * @return the {@link Future} which provides the {@link InvokeResult invocation result}
     */
    Future<InvokeResult> invokeAsync(InvokeRequest request);

    /**
     * Lists Lambda functions.
     *
     * @param request the {@link ListFunctionsRequest list functions request}
     * @return the {@link ListFunctionsResult list functions result}
     */
    ListFunctionsResult listFunctions(ListFunctionsRequest request);

    /**
     * Lists aliases of Lambda function.
     *
     * @param request the {@link ListAliasesRequest list aliases request}
     * @return the {@link ListFunctionsResult list aliases result}
     */
    ListAliasesResult listAliases(ListAliasesRequest request);

}
