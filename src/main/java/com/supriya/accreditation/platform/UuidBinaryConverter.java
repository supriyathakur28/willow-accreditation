package com.supriya.accreditation.platform;

import io.micronaut.core.convert.ConversionContext;
import io.micronaut.data.model.runtime.convert.AttributeConverter;
import jakarta.inject.Singleton;

import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * Stores a UUID as BINARY(16), most significant bits first. Big-endian keeps UUIDv7 IDs in time order
 * inside MySQL, so new rows append to the end of the primary-key index.
 * (By default Micronaut Data would write the 36-character string, which does not fit BINARY(16).)
 */
@Singleton
public class UuidBinaryConverter implements AttributeConverter<UUID, byte[]> {

    @Override
    public byte[] convertToPersistedValue(
            final UUID uuid,
            final ConversionContext context) {
        return toBytes(uuid);
    }

    @Override
    public UUID convertToEntityValue(
            final byte[] bytes,
            final ConversionContext context) {
        return fromBytes(bytes);
    }

    public static byte[] toBytes(final UUID uuid) {
        if (uuid == null) {
            return null;
        }
        return ByteBuffer.allocate(16)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits())
                .array();
    }

    public static UUID fromBytes(final byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        final ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
