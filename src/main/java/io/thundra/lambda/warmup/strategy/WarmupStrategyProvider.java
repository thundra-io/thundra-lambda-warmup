package io.thundra.lambda.warmup.strategy;

/**
 * Interface for implementations which provide the requested {@link WarmupStrategy}.
 *
 * @author serkan
 */
public interface WarmupStrategyProvider {

    /**
     * Provides the requested {@link WarmupStrategy}.
     *
     * @param warmupStrategyName name of the requested {@link WarmupStrategy}
     * @return the requested {@link WarmupStrategy}
     */
    WarmupStrategy getWarmupStrategy(String warmupStrategyName);

}
