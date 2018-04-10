package io.thundra.lambda.warmup.strategy;

import com.amazonaws.services.lambda.model.InvokeRequest;
import com.amazonaws.services.lambda.model.InvokeResult;
import com.amazonaws.services.lambda.runtime.Context;
import io.thundra.lambda.warmup.LambdaService;
import io.thundra.lambda.warmup.WarmupFunctionInfo;
import io.thundra.lambda.warmup.strategy.impl.StrategyAwareWarmupStrategy;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNull.notNullValue;
import static org.hamcrest.core.StringContains.containsString;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.*;

/**
 * @author serkan
 */
public class StrategyAwareWarmupStrategyTest {

    private Context context;

    private LambdaService lambdaService;

    @Before
    public void setup() {
        context = mock(Context.class);
        lambdaService = mock(LambdaService.class);
    }

    @Test
    public void shouldDelegateToWarmupStrategiesSuccessfully()
            throws IOException, ExecutionException, InterruptedException {
        WarmupStrategy warmupStrategy1 = mock(WarmupStrategy.class);
        WarmupStrategy warmupStrategy2 = mock(WarmupStrategy.class);

        StrategyAwareWarmupStrategy strategyAwareWarmupStrategy = new StrategyAwareWarmupStrategy(warmupStrategy1);

        when(context.getRemainingTimeInMillis()).thenReturn(2000);
        Future<InvokeResult> resultFuture = mock(Future.class);
        when(resultFuture.get()).thenReturn(new InvokeResult());
        when(lambdaService.invokeAsync(any(InvokeRequest.class))).thenReturn(resultFuture);

        Map<String, WarmupFunctionInfo> functionsToWarmup = new HashMap<String, WarmupFunctionInfo>();
        functionsToWarmup.put("testFunction1", new WarmupFunctionInfo());
        functionsToWarmup.put("testFunction2", new WarmupFunctionInfo().setWarmupStrategy(warmupStrategy2));
        strategyAwareWarmupStrategy.warmup(context, lambdaService, functionsToWarmup);

        Map<String, WarmupFunctionInfo> functionsToWarmup1 = new HashMap<String, WarmupFunctionInfo>();
        functionsToWarmup1.put("testFunction1", new WarmupFunctionInfo());
        verify(warmupStrategy1, times(1)).warmup(context, lambdaService, functionsToWarmup1);

        Map<String, WarmupFunctionInfo> functionsToWarmup2 = new HashMap<String, WarmupFunctionInfo>();
        functionsToWarmup2.put("testFunction2", new WarmupFunctionInfo().setWarmupStrategy(warmupStrategy2));
        verify(warmupStrategy2, times(1)).warmup(context, lambdaService, functionsToWarmup2);
    }

    @Test
    public void shouldThrowErrorsFromDelegatedWarmupStrategiesIfThereAreErrors()
            throws IOException, ExecutionException, InterruptedException {
        WarmupStrategy warmupStrategy1 = mock(WarmupStrategy.class);
        WarmupStrategy warmupStrategy2 = mock(WarmupStrategy.class);

        StrategyAwareWarmupStrategy strategyAwareWarmupStrategy = new StrategyAwareWarmupStrategy(warmupStrategy1);

        when(context.getRemainingTimeInMillis()).thenReturn(2000);
        Future<InvokeResult> resultFuture = mock(Future.class);
        when(resultFuture.get()).thenThrow(new RuntimeException("no warmup"));
        when(lambdaService.invokeAsync(any(InvokeRequest.class))).thenReturn(resultFuture);

        Map<String, WarmupFunctionInfo> functionsToWarmup = new HashMap<String, WarmupFunctionInfo>();
        functionsToWarmup.put("testFunction1", new WarmupFunctionInfo());
        functionsToWarmup.put("testFunction2", new WarmupFunctionInfo().setWarmupStrategy(warmupStrategy2));

        Map<String, WarmupFunctionInfo> functionsToWarmup1 = new HashMap<String, WarmupFunctionInfo>();
        functionsToWarmup1.put("testFunction1", new WarmupFunctionInfo());
        doThrow(new RuntimeException("no warmup")).when(warmupStrategy1).warmup(context, lambdaService, functionsToWarmup1);

        Map<String, WarmupFunctionInfo> functionsToWarmup2 = new HashMap<String, WarmupFunctionInfo>();
        functionsToWarmup2.put("testFunction2", new WarmupFunctionInfo().setWarmupStrategy(warmupStrategy2));
        doThrow(new RuntimeException("no warmup")).when(warmupStrategy2).warmup(context, lambdaService, functionsToWarmup2);

        try {
            strategyAwareWarmupStrategy.warmup(context, lambdaService, functionsToWarmup);
            fail("Should warmup fail with error");
        } catch (RuntimeException e) {
            Throwable[] suppressedExceptions = e.getSuppressed();
            assertThat(suppressedExceptions, is(notNullValue()));
            assertThat(suppressedExceptions.length, is(2));
            assertThat(suppressedExceptions[0].getMessage(), containsString("no warmup"));
            assertThat(suppressedExceptions[1].getMessage(), containsString("no warmup"));
        }
    }

}
