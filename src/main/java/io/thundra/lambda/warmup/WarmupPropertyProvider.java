package io.thundra.lambda.warmup;

import java.util.Set;

/**
 * Interface for implementations which provide warmup related properties.
 *
 * @author serkan
 */
public interface WarmupPropertyProvider {

    /**
     * Gets names of the all properties.
     *
     * @return names of the all properties
     */
    Set<String> getPropertyNames();

    /**
     * Gets <code>boolean</code> property.
     *
     * @param name name of the property
     * @return value of the property
     *         or <code>false</code> if it is not exist
     */
    Boolean getBoolean(String name);

    /**
     * Gets <code>boolean</code> property.
     *
     * @param name          name of the property
     * @param defaultValue  default value of the property
     *                      to be used if it is not exist
     * @return value of the property if it is exist,
     *         default value otherwise
     */
    default Boolean getBoolean(String name, Boolean defaultValue) {
        Boolean value = getBoolean(name);
        if (value == null) {
            return defaultValue;
        } else {
            return value;
        }
    }

    /**
     * Gets <code>integer</code> property.
     *
     * @param name name of the property
     * @return value of the property if it is exist
     *         <code>null</code> otherwise
     */
    Integer getInteger(String name);

    /**
     * Gets <code>integer</code> property.
     *
     * @param name          name of the property
     * @param defaultValue  default value of the property
     *                      to be used if it is not exist
     * @return value of the property if it is exist,
     *         default value otherwise
     */
    default Integer getInteger(String name, Integer defaultValue) {
        Integer value = getInteger(name);
        if (value == null) {
            return defaultValue;
        } else {
            return value;
        }
    }

    /**
     * Gets <code>float</code> property.
     *
     * @param name name of the property
     * @return value of the property if it is exist
     *         <code>null</code> otherwise
     */
    Float getFloat(String name);

    /**
     * Gets <code>float</code> property.
     *
     * @param name          name of the property
     * @param defaultValue  default value of the property
     *                      to be used if it is not exist
     * @return value of the property if it is exist,
     *         default value otherwise
     */
    default Float getFloat(String name, Float defaultValue) {
        Float value = getFloat(name);
        if (value == null) {
            return defaultValue;
        } else {
            return value;
        }
    }

    /**
     * Gets <code>long</code> property.
     *
     * @param name name of the property
     * @return value of the property if it is exist
     *         <code>null</code> otherwise
     */
    Long getLong(String name);

    /**
     * Gets <code>long</code> property.
     *
     * @param name          name of the property
     * @param defaultValue  default value of the property
     *                      to be used if it is not exist
     * @return value of the property if it is exist,
     *         default value otherwise
     */
    default Long getLong(String name, Long defaultValue) {
        Long value = getLong(name);
        if (value == null) {
            return defaultValue;
        } else {
            return value;
        }
    }

    /**
     * Gets <code>double</code> property.
     *
     * @param name name of the property
     * @return value of the property if it is exist
     *         <code>null</code> otherwise
     */
    Double getDouble(String name);

    /**
     * Gets <code>double</code> property.
     *
     * @param name          name of the property
     * @param defaultValue  default value of the property
     *                      to be used if it is not exist
     * @return value of the property if it is exist,
     *         default value otherwise
     */
    default Double getDouble(String name, Double defaultValue) {
        Double value = getDouble(name);
        if (value == null) {
            return defaultValue;
        } else {
            return value;
        }
    }

    /**
     * Gets <code>string</code> property.
     *
     * @param name name of the property
     * @return value of the property if it is exist
     *         <code>null</code> otherwise
     */
    String getString(String name);

    /**
     * Gets <code>string</code> property.
     *
     * @param name          name of the property
     * @param defaultValue  default value of the property
     *                      to be used if it is not exist
     * @return value of the property if it is exist,
     *         default value otherwise
     */
    default String getString(String name, String defaultValue) {
        String value = getString(name);
        if (value == null) {
            return defaultValue;
        } else {
            return value;
        }
    }

}
