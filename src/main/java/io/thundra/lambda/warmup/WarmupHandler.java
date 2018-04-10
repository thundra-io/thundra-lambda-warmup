package io.thundra.lambda.warmup;

import com.amazonaws.regions.Regions;
import com.amazonaws.services.lambda.AWSLambdaAsyncClient;
import com.amazonaws.services.lambda.model.*;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.util.StringUtils;
import com.opsgenie.aws.core.property.AwsPropertyAccessors;
import com.opsgenie.core.initialize.EnvironmentInitializerManager;
import com.opsgenie.core.instance.InstanceProvider;
import com.opsgenie.core.instance.InstanceScope;
import com.opsgenie.core.util.ExceptionUtil;
import com.opsgenie.sirocco.api.util.LambdaUtil;
import io.thundra.lambda.warmup.impl.SdkLambdaService;
import io.thundra.lambda.warmup.impl.SystemPropertyWarmupPropertyProvider;
import io.thundra.lambda.warmup.strategy.WarmupStrategy;
import io.thundra.lambda.warmup.strategy.WarmupStrategyProvider;
import io.thundra.lambda.warmup.strategy.impl.StandardWarmupStrategy;
import io.thundra.lambda.warmup.strategy.impl.StandardWarmupStrategyProvider;
import io.thundra.lambda.warmup.strategy.impl.StrategyAwareWarmupStrategy;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.util.*;

/**
 * <p>
 *      AWS Lambda {@link RequestHandler} implementation which
 *      triggers warmup action through {@link WarmupStrategy}s
 *      for configured/discovered functions.
 * </p>
 * This handler needs some permissions to do its job.
 * <ul>
 *      <li>
 *          <code>lambda:InvokeFunction</code>:
 *          This permission is needed for invoking functions to warmup
 *      </li>
 *      <li>
 *          <code>lambda:ListAliases</code>:
 *          This permission is needed when the alias discovery is used (enabled by default)
 *          for invoking functions by using alias as qualifier to warmup.
 *      </li>
 *      <li>
 *          <code>lambda:ListFunctions</code>:
 *          This permission is needed when any configuration discovery is used (enabled by default)
 *          for retrieving configurations of functions to warmup.
 *      </li>
 * </ul>
 *
 * @author serkan
 */
public class WarmupHandler implements RequestHandler<Object, Object> {

    static {
        init();
        EnvironmentInitializerManager.ensureInitialized();
    }

    private static final Logger LOGGER = Logger.getLogger(WarmupHandler.class);

    /**
     * Prefix for property names to declare functions to warmup and their configurations.
     * Multiple functions and their configurations can be specified with this prefix such as
     * <code>thundra.lambda.warmup.function1</code>, <code>thundra.lambda.warmup.function2</code>, ...
     * Besides function definition, configuration specification is also supported as key-value after function definition.
     * This property is used in <code>thundra.lambda.warmup.function...[conf1=val1;conf2=val2;...]</code> format
     * by appending configurations in key-value (separated by <code>=</code>) after function definition
     * between <code>[</code> and <code>]</code> characters and separating each of them by <code>;</code> character.
     * Note that configuration part is optional. The following configurations are supported through this property:
     * <ul>
     *      <li>
     *          <code>alias</code>:
     *          Configures alias to be used as qualifier while invoking the defined functions with warmup request.
     *      <li>
     *      <li>
     *          <code>warmupStrategy</code>:
     *          Configures name of the {@link WarmupStrategy} implementation to be used
     *          while warming-up the defined function.
     *      </li>
     *      <li>
     *          <code>invocationCount</code>:
     *          Configures concurrent invocation count for the defined function to warmup.
     *      </li>
     *      <li>
     *          <code>invocationData</code>:
     *          Configures invocation data to be used as invocation request while warming-up the defined function.
     *          By default empty message is used.
     *      </li>
     * </ul>
     */
    public static final String WARMUP_FUNCTION_DECLARATION_PROP_NAME_PREFIX =
            "thundra.lambda.warmup.function";

    /**
     * Name of the <code>boolean</code> typed property
     * which disables discovery mechanism for all configurations.
     */
    public static final String DISABLE_ALL_DISCOVERIES_PROP_NAME =
            "thundra.lambda.warmup.disableAllDiscoveries";

    /**
     * Name of the <code>boolean</code> typed environment variable
     * to be used for discovering Lambda functions to warmup.
     * If a Lambda function wants to be warmed-up,
     * it can publish itself by having this environment variable
     * as enabled (<code>true</code>).
     * Then, this handler will assume that this Lambda function want to be warmed-up
     * and will add it to its function list to warmup.
     */
    public static final String WARMUP_AWARE_ENV_VAR_NAME =
            "thundra_lambda_warmup_warmupAware";
    /**
     * Name of the <code>boolean</code> typed property which disables
     * discovery mechanism for warmup aware functions
     * specified by {@link #WARMUP_AWARE_ENV_VAR_NAME}.
     */
    public static final String DISABLE_WARMUP_AWARE_DISCOVERY_PROP_NAME =
            "thundra.lambda.warmup.disableWarmupAwareDiscovery";

    /**
     * Name of the <code>string</code> typed environment variable
     * to be used for discovering group name configuration
     * of Lambda functions to warmup.
     * If a Lambda function has different group name than
     * this handler's group name (specified by {@link #WARMUP_GROUP_NAME_PROP_NAME}),
     * it is not discovered and warmed-up.
     */
    public static final String WARMUP_GROUP_NAME_ENV_VAR_NAME =
            "thundra_lambda_warmup_warmupGroupName";
    /**
     * Name of the <code>string</code> typed property which configures
     * group name of this handler.
     * If warmup group name is specified by this property for this handler,
     * this handler only discovers and warms-up
     * Lambda functions in the same warmup group
     * (having same warmup group name specified by {@link #WARMUP_GROUP_NAME_ENV_VAR_NAME}).
     */
    public static final String WARMUP_GROUP_NAME_PROP_NAME =
            "thundra.lambda.warmup.groupName";

    /**
     * Name of the <code>string</code> typed property which configures
     * name of the {@link WarmupStrategy} implementation to be used.
     */
    public static final String WARMUP_STRATEGY_PROP_NAME =
            "thundra.lambda.warmup.strategy";
    /**
     * Default value for {@link #WARMUP_STRATEGY_PROP_NAME} property.
     * The default value is {@link StrategyAwareWarmupStrategy#NAME}.
     */
    public static final String DEFAULT_WARMUP_STRATEGY_NAME = StrategyAwareWarmupStrategy.NAME;

    /**
     * Name of the <code>string</code> typed environment variable
     * to be used for discovering specific warmup strategy name configuration
     * of Lambda functions to warmup.
     */
    public static final String WARMUP_STRATEGY_ENV_VAR_NAME =
            "thundra_lambda_warmup_warmupStrategy";
    /**
     * Name of the <code>boolean</code> typed property which disables
     * discovery mechanism for warmup strategy name configurations
     * specified by {@link #WARMUP_STRATEGY_ENV_VAR_NAME}.
     */
    public static final String DISABLE_WARMUP_STRATEGY_DISCOVERY_PROP_NAME =
            "thundra.lambda.warmup.disableWarmupStrategyDiscovery";

    /**
     * Name of the <code>string</code> typed property which
     * configures invocation data to be used as invocation request
     * while warming-up. By default empty message is used.
     */
    public static final String INVOCATION_DATA_PROP_NAME =
            "thundra.lambda.warmup.invocationData";
    /**
     * Name of the <code>string</code> typed environment variable
     * to be used for discovering specific warmup invocation data configuration
     * of Lambda functions to warmup.
     */
    public static final String INVOCATION_DATA_ENV_VAR_NAME =
            "thundra_lambda_warmup_warmupInvocationData";
    /**
     * Name of the <code>boolean</code> typed property which disables
     * discovery mechanism for warmup invocation data configurations
     * specified by {@link #INVOCATION_DATA_ENV_VAR_NAME}.
     */
    public static final String DISABLE_INVOCATION_DATA_DISCOVERY_PROP_NAME =
            "thundra.lambda.warmup.disableWarmupInvocationDataDiscovery";

    /**
     * Name of the <code>integer</code> typed environment variable
     * to be used for discovering specific warmup invocation count configuration
     * of Lambda functions to warmup.
     */
    public static final String INVOCATION_COUNT_ENV_VAR_NAME =
            "thundra_lambda_warmup_warmupInvocationCount";
    /**
     * Name of the <code>boolean</code> typed property which disables
     * discovery mechanism for warmup invocation count configurations
     * specified by {@link #INVOCATION_COUNT_ENV_VAR_NAME}.
     */
    public static final String DISABLE_INVOCATION_COUNT_DISCOVERY_PROP_NAME =
            "thundra.lambda.warmup.disableWarmupInvocationCountDiscovery";

    /**
     * Name of the <code>boolean</code> typed property which disables
     * alias discovery mechanism to be used as qualifier while invoking
     * Lambda functions to warmup.
     * When alias discovery mechanism is active (active by default),
     * alias with the latest version number is used as qualifier
     * on invocation.
     */
    public static final String DISABLE_ALIAS_DISCOVERY_PROP_NAME =
            "thundra.lambda.warmup.disableAliasDiscovery";

    public static final LambdaService DEFAULT_LAMBDA_SERVICE =
            createDefaultLambdaService();
    public static final WarmupPropertyProvider DEFAULT_WARMUP_PROPERTY_PROVIDER =
            new SystemPropertyWarmupPropertyProvider();
    public static final WarmupStrategyProvider DEFAULT_WARMUP_STRATEGY_PROVIDER =
            new StandardWarmupStrategyProvider();
    public static final WarmupStrategy DEFAULT_WARMUP_STRATEGY =
            createDefaultWarmupStrategy(DEFAULT_WARMUP_PROPERTY_PROVIDER);

    protected final LambdaService lambdaService;
    protected final WarmupPropertyProvider warmupPropertyProvider;
    protected final WarmupStrategyProvider warmupStrategyProvider;
    protected final WarmupStrategy warmupStrategy;
    protected final Map<String, WarmupFunctionInfo> registeredFunctionsToWarmup =
            new HashMap<String, WarmupFunctionInfo>();

    protected final boolean disableAllDiscoveries;
    protected final boolean disableWarmupAwareDiscovery;
    protected final String warmupGroupName;
    protected final String warmupStrategyName;
    protected final boolean disableWarmupStrategyDiscovery;
    protected final String invocationData;
    protected final boolean disableInvocationDataDiscovery;
    protected final boolean disableInvocationCountDiscovery;
    protected final boolean disableAliasDiscovery;

    private static void init() {
        Map<String, String> envMap = System.getenv();
        for (String envVarName : envMap.keySet()) {
            String envVarValue = envMap.get(envVarName).trim();
            String processedEnvVarName = envVarName.replace("_", ".");
            System.setProperty(processedEnvVarName, envVarValue);
        }
    }

    private static LambdaService createDefaultLambdaService() {
        AWSLambdaAsyncClient lambdaClient = new AWSLambdaAsyncClient(AwsPropertyAccessors.getDefaultCredentialsProvider());
        String regionStr = LambdaUtil.getRegion();
        if (StringUtils.hasValue(regionStr)) {
            lambdaClient.withRegion(Regions.fromName(regionStr));
        }
        return new SdkLambdaService(lambdaClient);
    }

    protected static String getWarmupStartegyName(WarmupStrategy warmupStrategy) {
        if (warmupStrategy instanceof StrategyAwareWarmupStrategy) {
            return ((StrategyAwareWarmupStrategy) warmupStrategy).getWarmupStrategy().getName();
        } else {
            return warmupStrategy.getName();
        }
    }

    public static WarmupStrategy createDefaultWarmupStrategy(WarmupPropertyProvider warmupPropertyProvider) {
        return createDefaultWarmupStrategy(warmupPropertyProvider, DEFAULT_WARMUP_STRATEGY_PROVIDER);
    }

    public static WarmupStrategy createDefaultWarmupStrategy(WarmupPropertyProvider warmupPropertyProvider,
                                                             WarmupStrategyProvider warmupStrategyProvider) {
        String warmupStrategyName =
                warmupPropertyProvider.getString(
                        WARMUP_STRATEGY_PROP_NAME,
                        DEFAULT_WARMUP_STRATEGY_NAME);
        WarmupStrategy configuredWarmupStrategy = warmupStrategyProvider.getWarmupStrategy(warmupStrategyName);
        if (configuredWarmupStrategy == null) {
            LOGGER.info("There is no configured warmup strategy. Going on with standard warmup strategy ...");
            return new StrategyAwareWarmupStrategy(
                    InstanceProvider.getInstance(
                            StandardWarmupStrategy.class,
                            InstanceScope.GLOBAL));
        } else {
            return new StrategyAwareWarmupStrategy(configuredWarmupStrategy);
        }
    }

    public WarmupHandler() {
        this(DEFAULT_LAMBDA_SERVICE,
             DEFAULT_WARMUP_PROPERTY_PROVIDER,
             DEFAULT_WARMUP_STRATEGY_PROVIDER,
             DEFAULT_WARMUP_STRATEGY);
    }

    public WarmupHandler(LambdaService lambdaService,
                         WarmupPropertyProvider warmupPropertyProvider,
                         WarmupStrategyProvider warmupStrategyProvider,
                         WarmupStrategy warmupStrategy) {
        this.lambdaService =
                lambdaService != null
                        ? lambdaService
                        : DEFAULT_LAMBDA_SERVICE;
        this.warmupPropertyProvider =
                warmupPropertyProvider != null
                        ? warmupPropertyProvider
                        : DEFAULT_WARMUP_PROPERTY_PROVIDER;
        this.warmupStrategyProvider =
                warmupStrategyProvider != null
                        ? warmupStrategyProvider
                        : DEFAULT_WARMUP_STRATEGY_PROVIDER;
        this.warmupStrategy =
                warmupStrategy != null
                        ? warmupStrategy
                        : createDefaultWarmupStrategy(warmupPropertyProvider);

        this.disableAllDiscoveries =
                warmupPropertyProvider.getBoolean(DISABLE_ALL_DISCOVERIES_PROP_NAME);
        this.disableWarmupAwareDiscovery =
                warmupPropertyProvider.getBoolean(DISABLE_WARMUP_AWARE_DISCOVERY_PROP_NAME);
        this.warmupGroupName =
                warmupPropertyProvider.getString(WARMUP_GROUP_NAME_PROP_NAME);
        this.warmupStrategyName =
                warmupPropertyProvider.getString(
                        WARMUP_STRATEGY_PROP_NAME,
                        DEFAULT_WARMUP_STRATEGY_NAME);
        this.disableWarmupStrategyDiscovery =
                warmupPropertyProvider.getBoolean(DISABLE_WARMUP_STRATEGY_DISCOVERY_PROP_NAME);
        this.invocationData =
                warmupPropertyProvider.getString(INVOCATION_DATA_PROP_NAME);
        this.disableInvocationDataDiscovery =
                warmupPropertyProvider.getBoolean(DISABLE_INVOCATION_DATA_DISCOVERY_PROP_NAME);
        this.disableInvocationCountDiscovery =
                warmupPropertyProvider.getBoolean(DISABLE_INVOCATION_COUNT_DISCOVERY_PROP_NAME);
        this.disableAliasDiscovery =
                warmupPropertyProvider.getBoolean(DISABLE_ALIAS_DISCOVERY_PROP_NAME);

        LOGGER.info("Using " + getWarmupStartegyName(warmupStrategy) + " warmup strategy ...");

        // Discover registered functions
        for (String propertyName : warmupPropertyProvider.getPropertyNames()) {
            if (propertyName.startsWith(WARMUP_FUNCTION_DECLARATION_PROP_NAME_PREFIX)) {
                String functionDeclarationsValue = warmupPropertyProvider.getString(propertyName);
                String[] functionDeclarations = functionDeclarationsValue.split(",");
                for (String functionDeclaration : functionDeclarations) {
                    functionDeclaration = functionDeclaration.trim();
                    if (StringUtils.isNullOrEmpty(functionDeclaration)) {
                        continue;
                    }
                    int infoStartIdx = functionDeclaration.indexOf("[");
                    int infoEndIdx = functionDeclaration.indexOf("]");
                    String functionName;
                    if (infoStartIdx > 0) {
                        functionName = functionDeclaration.substring(0, infoStartIdx);
                    } else {
                        functionName = functionDeclaration;
                    }
                    WarmupFunctionInfo info = registeredFunctionsToWarmup.get(functionName);
                    if (info == null) {
                        info = new WarmupFunctionInfo();
                        info.invocationData = invocationData;
                        registeredFunctionsToWarmup.put(functionName, info);
                    }
                    if (infoStartIdx > 0 && infoEndIdx > 0) {
                        String infoStr = functionDeclaration.substring(infoStartIdx + 1, infoEndIdx);
                        String[] infoStrParts = infoStr.split(";");
                        for (String infoStrPart : infoStrParts) {
                            infoStrPart = infoStrPart.trim();
                            String[] infoKeyValues = infoStrPart.split("=");
                            if (infoKeyValues.length != 2) {
                                throw new IllegalArgumentException(
                                        "Function informations must be in 'key=value' format!");
                            }
                            String infoKey = infoKeyValues[0].trim();
                            String infoValue = infoKeyValues[1].trim();
                            handleInfo(info, infoKey, infoValue);
                        }
                    }
                }
            }
        }

        LOGGER.info("Registered functions to warmup: " + registeredFunctionsToWarmup);
    }

    protected void handleInfo(WarmupFunctionInfo info, String infoKey, String infoValue) {
        if ("alias".equalsIgnoreCase(infoKey)) {
            info.alias = infoValue;
        } else if ("warmupStrategy".equalsIgnoreCase(infoKey)) {
            info.warmupStrategy = warmupStrategyProvider.getWarmupStrategy(infoValue);
            if (info.warmupStrategy == null) {
                throw new IllegalArgumentException("Unknown warmup strategy: " + infoValue);
            }
        } else if ("invocationCount".equalsIgnoreCase(infoKey)) {
            info.invocationCount = Integer.parseInt(infoValue);
        } else if ("invocationData".equalsIgnoreCase(infoKey)) {
            info.invocationData = infoValue;
        } else {
            throw new IllegalArgumentException("Not supported function information key: " + infoKey);
        }
    }

    protected Map<String, WarmupFunctionInfo> getFunctionsToWarmup() {
        Map<String, WarmupFunctionInfo> functionsToWarmup =
                new HashMap<String, WarmupFunctionInfo>(registeredFunctionsToWarmup.size());
        functionsToWarmup.putAll(registeredFunctionsToWarmup);

        if (!disableAllDiscoveries && !disableWarmupAwareDiscovery) {
            try {
                String marker = null;
                do {
                    // Discover warmup aware functions
                    ListFunctionsRequest listFunctionsRequest = new ListFunctionsRequest();
                    if (marker != null) {
                        listFunctionsRequest.withMarker(marker);
                    }
                    ListFunctionsResult listFunctionsResult = lambdaService.listFunctions(listFunctionsRequest);
                    if (listFunctionsResult == null) {
                        break;
                    }
                    marker = listFunctionsResult.getNextMarker();
                    for (FunctionConfiguration fc : listFunctionsResult.getFunctions()) {
                        EnvironmentResponse er = fc.getEnvironment();
                        if (er != null) {
                            Map<String, String> variables = er.getVariables();
                            if (variables != null) {
                                String warmupAwareValue = variables.get(WARMUP_AWARE_ENV_VAR_NAME);
                                if (Boolean.parseBoolean(warmupAwareValue)) {
                                    boolean skip = false;
                                    if (StringUtils.hasValue(warmupGroupName)) {
                                        String groupName = variables.get(WARMUP_GROUP_NAME_ENV_VAR_NAME);
                                        if (!warmupGroupName.equalsIgnoreCase(groupName)) {
                                            skip = true;
                                        }
                                    }
                                    if (!skip) {
                                        String functionName = fc.getFunctionName();
                                        WarmupFunctionInfo info = functionsToWarmup.get(functionName);
                                        if (info == null) {
                                            info = new WarmupFunctionInfo();
                                            info.invocationData = invocationData;
                                            functionsToWarmup.put(functionName, info);
                                        }
                                        handleConfig(fc, info);
                                        LOGGER.info("Auto discovered function to warmup: " + fc.getFunctionName());
                                    }
                                }
                            }
                        }
                    }
                } while (StringUtils.hasValue(marker));
            } catch (Throwable t) {
                LOGGER.error(
                        "Error occurred while discovering warmup functions! " +
                        "Skipping warmup function discovery ...", t);
            }
        }

        LOGGER.info("Functions to warmup: " + functionsToWarmup);

        return functionsToWarmup;
    }

    protected void handleConfig(FunctionConfiguration config, WarmupFunctionInfo info) {
        handleAliasConfig(config, info);
        handleWarmupStrategyConfig(config, info);
        handleInvocationCountConfig(config, info);
        handleInvocationDataConfig(config, info);
    }

    protected void handleAliasConfig(FunctionConfiguration config, WarmupFunctionInfo info) {
        if (disableAllDiscoveries || disableAliasDiscovery) {
            return;
        }
        try {
            String latestVersionedAlias = null;
            Map<Integer, String> versionedAliases =
                    new TreeMap<Integer, String>(new Comparator<Integer>() {
                        @Override
                        public int compare(Integer o1, Integer o2) {
                            return o2 - o1; // Descending order, max first
                        }
                    });
            ListAliasesResult listAliasesResult =
                    lambdaService.listAliases(
                            new ListAliasesRequest().withFunctionName(config.getFunctionName()));
            if (listAliasesResult == null) {
                return;
            }
            for (AliasConfiguration aliasConfiguration : listAliasesResult.getAliases()) {
                String aliasVersion = aliasConfiguration.getFunctionVersion();
                String aliasName = aliasConfiguration.getName();
                if ("$LATEST".equals(aliasVersion)) {
                    latestVersionedAlias = aliasConfiguration.getName();
                    break;
                } else {
                    Integer aliasVersionNo = Integer.parseInt(aliasVersion);
                    String existingAliasName = versionedAliases.put(aliasVersionNo, aliasName);
                    if (existingAliasName != null) {
                        LOGGER.warn(String.format(
                                "There are multiple aliases ('%s' and '%s') for function '%s' which are mapped to same version '%s'. " +
                                "So overriding and going on with '%s' ...",
                                existingAliasName, aliasName, config.getFunctionName(), aliasVersionNo, aliasName));
                    }
                }
            }
            if (latestVersionedAlias != null) {
                info.alias = latestVersionedAlias;
            } else if (!versionedAliases.isEmpty()) {
                String aliasWithMaxVersion = versionedAliases.values().iterator().next();
                info.alias = aliasWithMaxVersion;
            }
        } catch (Throwable t) {
            LOGGER.error(
                    String.format(
                            "Error occurred while discovering aliases for warmup function '%s'. " +
                            "So Skipping warmup function alias discovery ...",
                            config.getFunctionName()),
                    t);
        }
    }

    protected void handleWarmupStrategyConfig(FunctionConfiguration config, WarmupFunctionInfo info) {
        if (disableAllDiscoveries || disableWarmupStrategyDiscovery) {
            return;
        }
        EnvironmentResponse er = config.getEnvironment();
        if (er != null) {
            Map<String, String> variables = er.getVariables();
            if (variables != null) {
                String warmupStrategyName = variables.get(WARMUP_STRATEGY_ENV_VAR_NAME);
                if (StringUtils.hasValue(warmupStrategyName)) {
                    info.warmupStrategy = warmupStrategyProvider.getWarmupStrategy(warmupStrategyName);
                }
            }
        }
    }

    protected void handleInvocationCountConfig(FunctionConfiguration config, WarmupFunctionInfo info) {
        if (disableAllDiscoveries || disableInvocationCountDiscovery) {
            return;
        }
        EnvironmentResponse er = config.getEnvironment();
        if (er != null) {
            Map<String, String> variables = er.getVariables();
            if (variables != null) {
                String invocationCount = variables.get(INVOCATION_COUNT_ENV_VAR_NAME);
                if (StringUtils.hasValue(invocationCount)) {
                    info.invocationCount = Integer.parseInt(invocationCount);
                }
            }
        }
    }

    protected void handleInvocationDataConfig(FunctionConfiguration config, WarmupFunctionInfo info) {
        if (disableAllDiscoveries || disableInvocationDataDiscovery) {
            return;
        }
        EnvironmentResponse er = config.getEnvironment();
        if (er != null) {
            Map<String, String> variables = er.getVariables();
            if (variables != null) {
                String invocationData = variables.get(INVOCATION_DATA_ENV_VAR_NAME);
                if (StringUtils.hasValue(invocationData)) {
                    info.invocationData = invocationData;
                }
            }
        }
    }

    @Override
    public Object handleRequest(Object input, Context context) {
        String warmupStartegyName = getWarmupStartegyName(warmupStrategy);

        LOGGER.info("Starting warmup via " + warmupStartegyName + " warmup strategy ...");
        long start = System.currentTimeMillis();

        Map<String, WarmupFunctionInfo> functionsToWarmup = getFunctionsToWarmup();
        try {
            warmupStrategy.warmup(context, lambdaService, Collections.unmodifiableMap(functionsToWarmup));
        } catch (IOException e) {
            LOGGER.error("[ERROR] " + e.getMessage(), e);
            ExceptionUtil.sneakyThrow(e);
        }

        LOGGER.info("Finished warmup via " + warmupStartegyName +
                    " warmup strategy in " + (System.currentTimeMillis() - start) + " milliseconds");

        return null;
    }

}
