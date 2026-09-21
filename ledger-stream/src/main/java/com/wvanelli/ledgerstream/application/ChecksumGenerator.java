package com.wvanelli.ledgerstream.application;

import com.wvanelli.ledgerstream.domain.MonetaryAmount;
import com.wvanelli.ledgerstream.domain.SettlementType;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class ChecksumGenerator {
    private ChecksumGenerator() { }

    /** Length-prefixed UTF-8 fields, with an explicit null marker and attachment byte digest. */
    public static String generatePayloadChecksum(String accountId, String currency, MonetaryAmount amount,
            SettlementType settlementType, String description, Long originalSettlementId,
            String attachmentFileName, String attachmentContentType, String attachmentChecksum) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : new String[]{"ledgerstream-payload-v2", accountId, currency,
                    amount.toCanonicalString(), settlementType.name(), description,
                    originalSettlementId == null ? null : originalSettlementId.toString(),
                    attachmentFileName, attachmentContentType, attachmentChecksum}) {
                byte[] bytes = field == null ? new byte[0] : field.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(field == null ? -1 : bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 not available", impossible);
        }
    }
}
