package com.wvanelli.ledgerstream.application;

import com.wvanelli.ledgerstream.domain.MonetaryAmount;
import com.wvanelli.ledgerstream.domain.SettlementType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public class ChecksumGenerator {

    public static String generatePayloadChecksum(
            String accountId,
            String currency,
            MonetaryAmount amount,
            SettlementType settlementType,
            String description,
            Long originalSettlementId,
            String attachmentFileName,
            String attachmentContentType) {
        
        StringBuilder builder = new StringBuilder();
        builder.append(accountId).append("|");
        builder.append(currency).append("|");
        builder.append(amount.getValue().toPlainString()).append("|");
        builder.append(settlementType.name()).append("|");
        builder.append(description != null ? description : "").append("|");
        builder.append(originalSettlementId != null ? originalSettlementId.toString() : "").append("|");
        builder.append(attachmentFileName != null ? attachmentFileName : "").append("|");
        builder.append(attachmentContentType != null ? attachmentContentType : "");

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(builder.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
