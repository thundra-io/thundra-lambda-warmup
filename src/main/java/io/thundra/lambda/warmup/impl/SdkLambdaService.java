package io.thundra.lambda.warmup.impl;

import com.amazonaws.services.lambda.AWSLambdaAsyncClient;
import com.amazonaws.services.lambda.model.*;
import io.thundra.lambda.warmup.LambdaService;

import java.util.concurrent.Future;

/**
 * AWS SDK based {@link LambdaService} implementation.
 *
 * @author serkan
 */
public class SdkLambdaService implements LambdaService {

    private final AWSLambdaAsyncClient lambdaClient;

    public SdkLambdaService(AWSLambdaAsyncClient lambdaClient) {
        this.lambdaClient = lambdaClient;
    }

    public AWSLambdaAsyncClient getLambdaClient() {
        return lambdaClient;
    }

    @Override
    public InvokeResult invoke(InvokeRequest request) {
        return lambdaClient.invoke(request);
    }

    @Override
    public Future<InvokeResult> invokeAsync(InvokeRequest request) {
        return lambdaClient.invokeAsync(request);
    }

    @Override
    public ListFunctionsResult listFunctions(ListFunctionsRequest request) {
        return lambdaClient.listFunctions(request);
    }

    @Override
    public ListAliasesResult listAliases(ListAliasesRequest request) {
        return lambdaClient.listAliases(request);
    }

}
