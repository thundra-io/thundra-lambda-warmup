package io.thundra.lambda.warmup.strategy;

import com.amazonaws.services.lambda.model.InvokeRequest;
import com.amazonaws.services.lambda.model.InvokeResult;
import com.amazonaws.services.lambda.runtime.Context;
import io.thundra.lambda.warmup.LambdaService;
import io.thundra.lambda.warmup.WarmupFunctionInfo;
import io.thundra.lambda.warmup.impl.MapWarmupPropertyProvider;
import io.thundra.lambda.warmup.strategy.impl.StandardWarmupStrategy;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static io.thundra.lambda.warmup.strategy.impl.StandardWarmupStrategy.DEFAULT_INVOCATION_COUNT;
import static io.thundra.lambda.warmup.strategy.impl.StandardWarmupStrategy.ITERATION_COUNT_PROP_NAME;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.StringContains.containsString;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.*;

/**
 * @author serkan
 */
public class StandardWarmupStrategyTest {

    private Context context;

    private LambdaService lambdaService;

    @Before
    public void setup() {
        context = mock(Context.class);
        lambdaService = mock(LambdaService.class);
    }

    @Test
    public void shouldWarmupSuccessfully()
            throws IOException, ExecutionException, InterruptedException {
        Map<String, Object> warmupPropertyMap = new HashMap<String, Object>();
        warmupPropertyMap.put(ITERATION_COUNT_PROP_NAME, 1);
        StandardWarmupStrategy standardWarmupStrategy =
                new StandardWarmupStrategy(new MapWarmupPropertyProvider(warmupPropertyMap));

        when(context.getRemainingTimeInMillis()).thenReturn(2000);
        Future<InvokeResult> resultFuture = mock(Future.class);
        when(resultFuture.get()).thenReturn(new InvokeResult());
        when(lambdaService.invokeAsync(any(InvokeRequest.class))).thenReturn(resultFuture);

        Map<String, WarmupFunctionInfo> functionsToWarmup = new HashMap<String, WarmupFunctionInfo>();
        functionsToWarmup.put("testFunction", new WarmupFunctionInfo());
        standardWarmupStrategy.warmup(context, lambdaService, functionsToWarmup);

        verify(lambdaService, times(DEFAULT_INVOCATION_COUNT)).invokeAsync(any(InvokeRequest.class));
    }

    @Test
    public void shouldThrowErrorWhileWarmupIfThereIsError()
            throws IOException, ExecutionException, InterruptedException {
        Map<String, Object> warmupPropertyMap = new HashMap<String, Object>();
        warmupPropertyMap.put(ITERATION_COUNT_PROP_NAME, 1);
        StandardWarmupStrategy standardWarmupStrategy =
                new StandardWarmupStrategy(new MapWarmupPropertyProvider(warmupPropertyMap));

        when(context.getRemainingTimeInMillis()).thenReturn(2000);
        Future<InvokeResult> resultFuture = mock(Future.class);
        when(resultFuture.get()).thenThrow(new RuntimeException("no warmup"));
        when(lambdaService.invokeAsync(any(InvokeRequest.class))).thenReturn(resultFuture);

        Map<String, WarmupFunctionInfo> functionsToWarmup = new HashMap<String, WarmupFunctionInfo>();
        functionsToWarmup.put("testFunction", new WarmupFunctionInfo());
        try {
            standardWarmupStrategy.warmup(context, lambdaService, functionsToWarmup);
            fail("Should warmup fail with error");
        } catch (RuntimeException e) {
            assertThat(e.getMessage(), containsString("no warmup"));
        }

        verify(lambdaService, times(DEFAULT_INVOCATION_COUNT)).invokeAsync(any(InvokeRequest.class));
    }

}
