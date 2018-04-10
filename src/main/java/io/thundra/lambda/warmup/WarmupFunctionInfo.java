package io.thundra.lambda.warmup;

import io.thundra.lambda.warmup.strategy.WarmupStrategy;

/**
 * Holds information/configuration about Lambda function to warmup.
 *
 * @author serkan
 */
public class WarmupFunctionInfo {

    String alias;
    WarmupStrategy warmupStrategy;
    int invocationCount;
    String invocationData;

    public WarmupFunctionInfo() {
    }

    public String getAlias() {
        return alias;
    }

    public WarmupFunctionInfo setAlias(String alias) {
        this.alias = alias;
        return this;
    }

    public WarmupStrategy getWarmupStrategy() {
        return warmupStrategy;
    }

    public WarmupFunctionInfo setWarmupStrategy(WarmupStrategy warmupStrategy) {
        this.warmupStrategy = warmupStrategy;
        return this;
    }

    public int getInvocationCount() {
        return invocationCount;
    }

    public WarmupFunctionInfo setInvocationCount(int invocationCount) {
        this.invocationCount = invocationCount;
        return this;
    }

    public String getInvocationData() {
        return invocationData;
    }

    public WarmupFunctionInfo setInvocationData(String invocationData) {
        this.invocationData = invocationData;
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        WarmupFunctionInfo that = (WarmupFunctionInfo) o;

        if (invocationCount != that.invocationCount) return false;
        if (alias != null ? !alias.equals(that.alias) : that.alias != null) return false;
        if (warmupStrategy != null ? !warmupStrategy.equals(that.warmupStrategy) : that.warmupStrategy != null)
            return false;
        return invocationData != null ? invocationData.equals(that.invocationData) : that.invocationData == null;
    }

    @Override
    public int hashCode() {
        int result = alias != null ? alias.hashCode() : 0;
        result = 31 * result + (warmupStrategy != null ? warmupStrategy.hashCode() : 0);
        result = 31 * result + invocationCount;
        result = 31 * result + (invocationData != null ? invocationData.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "WarmupFunctionInfo{" +
                "alias='" + alias + '\'' +
                ", warmupStrategy=" + (warmupStrategy != null ? '\'' + (warmupStrategy.getName() + '\'') : "null") +
                ", invocationCount=" + invocationCount +
                ", invocationData=" + invocationData +
                '}';
    }

}
