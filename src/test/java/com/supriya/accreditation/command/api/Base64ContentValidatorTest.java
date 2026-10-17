package com.supriya.accreditation.command.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class Base64ContentValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {"ICAiQC8qIjogWyJzcmMvKiJdCiAgICB9CiAgfQp9Cg==", "QQ==", "QUI=", "QUJD", "+/+/"})
    void acceptsStandardBase64(final String value) {
        assertThat(Base64ContentValidator.isStandardBase64(value)).isTrue();
        Base64.getDecoder().decode(value); // and the JDK agrees
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "QQ", "QQ=", "Q===", "QQ=A", "not base64!", "-_-_", "QUJD\n"})
    void rejectsAnythingElse(final String value) {
        assertThat(Base64ContentValidator.isStandardBase64(value)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"QQ==", "QUI=", "QUJD", "ICAiQC8qIjogWyJzcmMvKiJdCiAgICB9CiAgfQp9Cg=="})
    void computesTheDecodedLengthExactly(final String value) {
        assertThat(Base64ContentValidator.decodedLength(value)).isEqualTo(Base64.getDecoder().decode(value).length);
    }

    @Test
    void allowsExactlyFiveMegabytesDecoded() {
        final int fiveMegabytes = 5 * 1024 * 1024;
        final String exactly = Base64.getEncoder().encodeToString(new byte[fiveMegabytes]);
        final String oneByteMore = Base64.getEncoder().encodeToString(new byte[fiveMegabytes + 1]);

        assertThat(Base64ContentValidator.decodedLength(exactly)).isEqualTo(fiveMegabytes);
        assertThat(Base64ContentValidator.decodedLength(oneByteMore)).isEqualTo(fiveMegabytes + 1);
        // Same encoded length as the 5 MB value, but no padding: decodes to one byte too many
        assertThat(Base64ContentValidator.decodedLength("A".repeat(exactly.length()))).isGreaterThan(fiveMegabytes);
    }
}
