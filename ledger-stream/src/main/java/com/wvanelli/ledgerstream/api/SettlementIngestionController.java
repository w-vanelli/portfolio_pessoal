package com.wvanelli.ledgerstream.api;

import com.wvanelli.ledgerstream.api.dto.SettlementIngestRequest;
import com.wvanelli.ledgerstream.api.dto.SettlementResponse;
import com.wvanelli.ledgerstream.application.RegisterSettlementCommand;
import com.wvanelli.ledgerstream.application.SettlementApplicationService;
import com.wvanelli.ledgerstream.application.SettlementRegistrationResult;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.UUID;

/**
 * REST controller for financial settlement event ingestion with strict idempotency barriers
 * and optional binary attachment promotion.
 * Maps to POST /api/v1/settlements in docs/openapi.yaml.
 */
@RestController
@RequestMapping("/api/v1/settlements")
public class SettlementIngestionController {

    private final SettlementApplicationService settlementApplicationService;

    public SettlementIngestionController(SettlementApplicationService settlementApplicationService) {
        this.settlementApplicationService = settlementApplicationService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SettlementResponse> ingestSettlement(
            @RequestHeader("X-Idempotency-Key") UUID idempotencyKey,
            @RequestPart("metadata") @Valid SettlementIngestRequest metadata,
            @RequestPart(value = "attachment", required = false) MultipartFile attachment
    ) throws IOException {
        String attachmentFileName = null;
        String attachmentContentType = null;
        InputStream attachmentStream = null;

        if (attachment != null && !attachment.isEmpty()) {
            attachmentFileName = attachment.getOriginalFilename();
            attachmentContentType = attachment.getContentType();
            attachmentStream = attachment.getInputStream();
        }

        RegisterSettlementCommand command = new RegisterSettlementCommand(
                idempotencyKey,
                metadata.accountId(),
                metadata.currency(),
                metadata.amount(),
                metadata.settlementType(),
                metadata.description(),
                metadata.originalSettlementId(),
                attachmentFileName,
                attachmentContentType,
                attachmentStream
        );

        SettlementRegistrationResult result = settlementApplicationService.registerSettlement(command);
        SettlementResponse responseBody = SettlementResponse.from(result.event());

        if (result.isNew()) {
            URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                    .path("/api/v1/settlements/{id}")
                    .buildAndExpand(result.event().getId())
                    .toUri();
            return ResponseEntity.created(location).body(responseBody);
        } else {
            return ResponseEntity.ok(responseBody);
        }
    }
}
