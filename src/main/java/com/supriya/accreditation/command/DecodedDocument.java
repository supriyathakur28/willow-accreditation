package com.supriya.accreditation.command;

import com.supriya.accreditation.platform.Sha256;

import java.util.Base64;

/**
 * The evidence document, decoded and hashed before the transaction starts so the blob work never lengthens
 * the time locks are held. The hash is of the file's bytes, so it matches {@code sha256sum} of the original.
 */
record DecodedDocument(
        String fileName,
        String mimeType,
        byte[] content,
        String sha256) {

    static DecodedDocument decode(
            final String name,
            final String mimeType,
            final String base64Content) {
        final byte[] content = Base64.getDecoder().decode(base64Content);
        return new DecodedDocument(name, mimeType, content, Sha256.hex(content));
    }
}
