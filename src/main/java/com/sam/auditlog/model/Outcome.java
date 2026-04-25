package com.sam.auditlog.model;

public enum Outcome {
    SUCCESS,
    DENIED,
    ERROR;

    public String dbValue() {
        return name().toLowerCase();
    }

    public static Outcome fromDb(String value) {
        return Outcome.valueOf(value.toUpperCase());
    }
}
