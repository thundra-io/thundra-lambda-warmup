package io.thundra.lambda.warmup;

import com.amazonaws.services.lambda.model.EnvironmentResponse;
import com.amazonaws.services.lambda.model.FunctionConfiguration;
import com.amazonaws.services.lambda.model.ListFunctionsRequest;
import com.amazonaws.services.lambda.model.ListFunctionsResult;
import com.amazonaws.services.lambda.runtime.Context;
import io.thundra.lambda.warmup.impl.MapWarmupPropertyProvider;
import io.thundra.lambda.warmup.strategy.WarmupStrategy;
import io.thundra.lambda.warmup.strategy.WarmupStrategyProvider;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static io.thundra.lambda.warmup.WarmupHandler.*;
import static org.mockito.Mockito.*;

/**
 * @author serkan
 */
public class WarmupHandlerTest {

    private Context context;

    private LambdaService lambdaService;

    @Before
    public void setup() {
        context = mock(Context.class);
        lambdaService = mock(LambdaService.class);
    }

    @Test
    public void shouldWarmupSuccessfully() throws IOException {
        WarmupStrategyProvider warmupStrategyProvider = mock(WarmupStrategyProvider.class);
        WarmupStrategy warmupStrategy1 = mock(WarmupStrategy.class);
        when(warmupStrategy1.getName()).thenReturn("warmupStrategy1");
        WarmupStrategy warmupStrategy2 = mock(WarmupStrategy.class);
        when(warmupStrategy2.getName()).thenReturn("warmupStrategy2");
        WarmupStrategy warmupStrategy3 = mock(WarmupStrategy.class);
        when(warmupStrategy3.getName()).thenReturn("warmupStrategy3");

        Map<String, Object> warmupPropertyMap = new HashMap<String, Object>();
        warmupPropertyMap.put(
                WARMUP_STRATEGY_PROP_NAME,
                "warmupStrategy1");
        warmupPropertyMap.put(
                WARMUP_FUNCTION_DECLARATION_PROP_NAME_PREFIX + "_1",
                "testFunction1");
        warmupPropertyMap.put(
                WARMUP_FUNCTION_DECLARATION_PROP_NAME_PREFIX + "_2",
                "testFunction2[warmupStrategy=warmupStrategy2]");
        WarmupPropertyProvider warmupPropertyProvider = new MapWarmupPropertyProvider(warmupPropertyMap);

        ListFunctionsResult listFunctionsResult =
                new ListFunctionsResult().
                    withFunctions(
                        new FunctionConfiguration().
                            withFunctionName("testFunction3").
                            withEnvironment(
                                new EnvironmentResponse().
                                    withVariables(
                                        new HashMap<String, String>() {{
                                            put(WARMUP_AWARE_ENV_VAR_NAME, "true");
                                            put(WARMUP_STRATEGY_ENV_VAR_NAME, "warmupStrategy3");
                                        }}
                                    )
                            )
                    );
        when(lambdaService.listFunctions(any(ListFunctionsRequest.class))).
                thenReturn(listFunctionsResult);
        when(warmupStrategyProvider.getWarmupStrategy("warmupStrategy1")).
                thenReturn(warmupStrategy1);
        when(warmupStrategyProvider.getWarmupStrategy("warmupStrategy2")).
                thenReturn(warmupStrategy2);
        when(warmupStrategyProvider.getWarmupStrategy("warmupStrategy3")).
                thenReturn(warmupStrategy3);

        WarmupHandler warmupHandler =
                new WarmupHandler(
                        lambdaService,
                        warmupPropertyProvider,
                        warmupStrategyProvider,
                        WarmupHandler.createDefaultWarmupStrategy(warmupPropertyProvider, warmupStrategyProvider));

        warmupHandler.handleRequest(new Object(), context);

        Map<String, WarmupFunctionInfo> functionsToWarmup1 = new HashMap<String, WarmupFunctionInfo>();
        functionsToWarmup1.put("testFunction1", new WarmupFunctionInfo());
        verify(warmupStrategy1, times(1)).warmup(context, lambdaService, functionsToWarmup1);

        Map<String, WarmupFunctionInfo> functionsToWarmup2 = new HashMap<String, WarmupFunctionInfo>();
        functionsToWarmup2.put("testFunction2", new WarmupFunctionInfo().setWarmupStrategy(warmupStrategy2));
        verify(warmupStrategy2, times(1)).warmup(context, lambdaService, functionsToWarmup2);

        Map<String, WarmupFunctionInfo> functionsToWarmup3 = new HashMap<String, WarmupFunctionInfo>();
        functionsToWarmup3.put("testFunction3", new WarmupFunctionInfo().setWarmupStrategy(warmupStrategy3));
        verify(warmupStrategy3, times(1)).warmup(context, lambdaService, functionsToWarmup3);
    }

}
