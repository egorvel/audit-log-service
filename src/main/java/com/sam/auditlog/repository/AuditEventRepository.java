package com.sam.auditlog.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.sam.auditlog.model.AuditEvent;

public interface AuditEventRepository extends JpaRepository<AuditEvent, String> {}
