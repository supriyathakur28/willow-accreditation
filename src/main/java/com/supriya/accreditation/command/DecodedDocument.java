package com.supriya.accreditation.command;

import com.supriya.accreditation.platform.Sha256;

import java.util.Base64;

/**
 * The evidence document, decoded and hashed before the transaction starts so the blob work never lengthens
 * the time locks are held.
 */
record DecodedDocument(
        String fileName,
        String mimeType,
        byte[] content,
        String sha256) {

    private static final int MAX_FILE_NAME = 255;

    static DecodedDocument decode(
            final String name,
            final String mimeType,
            final String base64Content) {
        final byte[] content = Base64.getDecoder().decode(base64Content);
        return new DecodedDocument(sanitise(name), mimeType, content, Sha256.hex(content));
    }

    // Keep only the last path segment and drop control characters, so the name is safe to show and store
    static String sanitise(final String name) {
        final String lastSegment = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        String cleaned = lastSegment.replaceAll("\\p{Cntrl}", "").strip();
        if (cleaned.isEmpty()) {
            cleaned = "document";
        }
        return cleaned.length() > MAX_FILE_NAME ? cleaned.substring(0, MAX_FILE_NAME) : cleaned;
    }
}
