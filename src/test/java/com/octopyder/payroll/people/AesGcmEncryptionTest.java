package com.octopyder.payroll.people;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesGcmEncryptionTest {

    private EncryptionService encryptionService;

    @BeforeEach
    void setUp() {
        // 32-byte (256-bit) test key encoded in Base64
        byte[] keyBytes = "12345678901234567890123456789012".getBytes(StandardCharsets.UTF_8);
        String base64Key = Base64.getEncoder().encodeToString(keyBytes);
        encryptionService = new EncryptionService(base64Key);
    }

    @Test
    void encryptAndDecryptRecoversOriginalPlaintext() {
        String plaintext = "9876543210123456";
        String ciphertext = encryptionService.encrypt(plaintext);

        assertThat(ciphertext).isNotNull();
        assertThat(ciphertext).isNotEqualTo(plaintext);
        assertThat(ciphertext).doesNotContain(plaintext);

        String decrypted = encryptionService.decrypt(ciphertext);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    void encryptProducesDifferentCiphertextsForSameInputDueToRandomIv() {
        String plaintext = "1234567890";
        String c1 = encryptionService.encrypt(plaintext);
        String c2 = encryptionService.encrypt(plaintext);

        assertThat(c1).isNotEqualTo(c2);
        assertThat(encryptionService.decrypt(c1)).isEqualTo(plaintext);
        assertThat(encryptionService.decrypt(c2)).isEqualTo(plaintext);
    }

    @Test
    void decryptFailsWhenCiphertextIsTamperedWith() {
        String plaintext = "SECRET_ACCOUNT_NUMBER";
        String ciphertext = encryptionService.encrypt(plaintext);

        byte[] raw = Base64.getDecoder().decode(ciphertext);
        raw[raw.length - 1] ^= 0x01; // flip last byte (tamper authentication tag)
        String tampered = Base64.getEncoder().encodeToString(raw);

        assertThatThrownBy(() -> encryptionService.decrypt(tampered))
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    void maskAccountNumberMasksAllButLastFourDigits() {
        assertThat(encryptionService.mask("12345678901234")).isEqualTo("XXXX-XXXX-1234");
        assertThat(encryptionService.mask("1234")).isEqualTo("XXXX-XXXX-1234");
        assertThat(encryptionService.mask(null)).isNull();
    }
}
