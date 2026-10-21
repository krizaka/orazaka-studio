package com.krizaka.orazaka.studioservice.infrastructure.adapter.rest;

import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The operator audit of the Studio service, read by the console's Audit log (ADR-085). Ids and
 * outcomes only.
 */
@RestController
@RequestMapping("/api/v1/studios/admin/audit")
class OperatorAuditController {

  private final OperatorAuditStore store;

  OperatorAuditController(OperatorAuditStore store) {
    this.store = store;
  }

  @GetMapping
  @PreAuthorize("hasRole('ADMIN')")
  List<OperatorAuditStore.Stored> recent(@RequestParam(defaultValue = "200") int limit) {
    return store.recent(Math.min(500, Math.max(1, limit)));
  }
}
