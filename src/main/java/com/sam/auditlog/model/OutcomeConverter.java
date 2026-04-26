package com.sam.auditlog.model;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class OutcomeConverter implements AttributeConverter<Outcome, String> {

    @Override
    public String convertToDatabaseColumn(Outcome outcome) {
        return outcome == null ? null : outcome.dbValue();
    }

    @Override
    public Outcome convertToEntityAttribute(String value) {
        return value == null ? null : Outcome.fromDb(value);
    }
}
