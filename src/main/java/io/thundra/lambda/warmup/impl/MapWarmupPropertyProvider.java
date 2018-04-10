package io.thundra.lambda.warmup.impl;

import io.thundra.lambda.warmup.WarmupPropertyProvider;

import java.util.Map;
import java.util.Set;

/**
 * {@link Map} based {@link WarmupPropertyProvider} implementation.
 *
 * @author serkan
 */
public class MapWarmupPropertyProvider implements WarmupPropertyProvider {

    private final Map<String, Object> props;

    public MapWarmupPropertyProvider(Map<String, Object> props) {
        this.props = props;
    }

    @Override
    public Set<String> getPropertyNames() {
        return props.keySet();
    }

    @Override
    public Boolean getBoolean(String name) {
        Boolean value = (Boolean) props.get(name);
        if (value == null) {
            return false;
        } else {
            return value;
        }
    }

    @Override
    public Integer getInteger(String name) {
        return (Integer) props.get(name);
    }

    @Override
    public Float getFloat(String name) {
        return (Float) props.get(name);
    }

    @Override
    public Long getLong(String name) {
        return (Long) props.get(name);
    }

    @Override
    public Double getDouble(String name) {
        return (Double) props.get(name);
    }

    @Override
    public String getString(String name) {
        return (String) props.get(name);
    }

}
