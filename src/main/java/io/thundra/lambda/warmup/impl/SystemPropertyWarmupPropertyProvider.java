package io.thundra.lambda.warmup.impl;

import io.thundra.lambda.warmup.WarmupPropertyProvider;

import java.util.Set;

/**
 * System property based {@link WarmupPropertyProvider} implementation.
 *
 * @author serkan
 */
public class SystemPropertyWarmupPropertyProvider implements WarmupPropertyProvider {

    @Override
    public Set<String> getPropertyNames() {
        return System.getProperties().stringPropertyNames();
    }

    @Override
    public Boolean getBoolean(String name) {
        return Boolean.getBoolean(name);
    }

    @Override
    public Integer getInteger(String name) {
        return Integer.getInteger(name);
    }

    @Override
    public Float getFloat(String name) {
        String value = System.getProperty(name);
        if (value == null) {
            return null;
        }
        return Float.parseFloat(value);
    }

    @Override
    public Long getLong(String name) {
        return Long.getLong(name);
    }

    @Override
    public Double getDouble(String name) {
        String value = System.getProperty(name);
        if (value == null) {
            return null;
        }
        return Double.parseDouble(value);
    }

    @Override
    public String getString(String name) {
        return System.getProperty(name);
    }

}
