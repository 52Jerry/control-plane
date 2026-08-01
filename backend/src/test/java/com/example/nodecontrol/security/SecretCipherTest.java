package com.example.nodecontrol.security;

import com.example.nodecontrol.config.ControlPlaneProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecretCipherTest {

    @Test
    void encryptsWithRandomizedAesGcmPayloadsAndDecryptsThem() {
        ControlPlaneProperties properties = new ControlPlaneProperties();
        properties.getSecurity().setEncryptionKey("test-key-with-enough-entropy");
        SecretCipher cipher = new SecretCipher(properties);

        String first = cipher.encrypt("node-api-token");
        String second = cipher.encrypt("node-api-token");

        assertThat(first).startsWith("enc:v1:").isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo("node-api-token");
        assertThat(cipher.decrypt(second)).isEqualTo("node-api-token");
    }
}
