package com.east.app.config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import com.east.app.vault.EncryptedPayload;
import com.east.app.vault.VaultCryptoService;
import com.east.app.vault.VaultService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.support.GenericConversionService;
import org.springframework.core.serializer.support.SerializationDelegate;
import org.springframework.util.ClassUtils;

/** Encrypts Spring Session's serialized attributes with the installation vault key. */
@Configuration
public class SessionAttributeEncryptionConfiguration {

    private static final byte[] MAGIC = "EASTSESSION".getBytes(StandardCharsets.US_ASCII);
    private static final byte VERSION = 1;
    private static final byte[] AAD_DOMAIN = "spring-session-attribute".getBytes(StandardCharsets.US_ASCII);

    @Bean(name = "springSessionConversionService")
    ConversionService springSessionConversionService(VaultService vaultService, VaultCryptoService cryptoService) {
        GenericConversionService conversionService = new GenericConversionService();
        SerializationDelegate serializer = new SerializationDelegate(ClassUtils.getDefaultClassLoader());
        conversionService.addConverter(new SessionAttributeEncryptingConverter(vaultService, cryptoService, serializer));
        conversionService.addConverter(new SessionAttributeDecryptingConverter(vaultService, cryptoService, serializer));
        return conversionService;
    }

    private record SessionAttributeEncryptingConverter(
            VaultService vaultService,
            VaultCryptoService cryptoService,
            SerializationDelegate serializer) implements Converter<Object, byte[]> {

        @Override
        public byte[] convert(Object source) {
            byte[] serialized = null;
            byte[] packed = null;
            byte[] aad = associatedData();
            EncryptedPayload encrypted = null;
            try (WipingByteArrayOutputStream output = new WipingByteArrayOutputStream()) {
                serializer.serialize(source, output);
                serialized = output.copyAndWipe();
                encrypted = vaultService.encryptLocal(serialized, aad);
                packed = cryptoService.pack(encrypted);
                byte[] envelope = new byte[MAGIC.length + 1 + packed.length];
                System.arraycopy(MAGIC, 0, envelope, 0, MAGIC.length);
                envelope[MAGIC.length] = VERSION;
                System.arraycopy(packed, 0, envelope, MAGIC.length + 1, packed.length);
                return envelope;
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to persist the encrypted session attribute.", exception);
            } finally {
                wipe(serialized);
                wipe(packed);
                wipe(aad);
                if (encrypted != null) encrypted.destroy();
            }
        }
    }

    private record SessionAttributeDecryptingConverter(
            VaultService vaultService,
            VaultCryptoService cryptoService,
            SerializationDelegate serializer) implements Converter<byte[], Object> {

        @Override
        public Object convert(byte[] envelope) {
            if (!hasSupportedHeader(envelope)) {
                return null;
            }
            byte[] packed = Arrays.copyOfRange(envelope, MAGIC.length + 1, envelope.length);
            byte[] plaintext = null;
            byte[] aad = associatedData();
            EncryptedPayload encrypted = null;
            try {
                encrypted = cryptoService.unpack(packed);
                plaintext = vaultService.decryptLocal(encrypted, aad);
                return serializer.deserialize(new ByteArrayInputStream(plaintext));
            } catch (IOException | RuntimeException ignored) {
                // An unavailable/lost key or invalid attribute must not block vault recovery routes.
                // Treat it as absent so Spring Security cannot restore an unauthenticated context.
                return null;
            } finally {
                wipe(packed);
                wipe(plaintext);
                wipe(aad);
                if (encrypted != null) encrypted.destroy();
            }
        }
    }

    private static boolean hasSupportedHeader(byte[] envelope) {
        if (envelope == null || envelope.length <= MAGIC.length + 1 + 28) {
            return false;
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (envelope[i] != MAGIC[i]) {
                return false;
            }
        }
        return envelope[MAGIC.length] == VERSION;
    }

    private static byte[] associatedData() {
        byte[] aad = new byte[MAGIC.length + 1 + AAD_DOMAIN.length];
        System.arraycopy(MAGIC, 0, aad, 0, MAGIC.length);
        aad[MAGIC.length] = VERSION;
        System.arraycopy(AAD_DOMAIN, 0, aad, MAGIC.length + 1, AAD_DOMAIN.length);
        return aad;
    }

    private static void wipe(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }

    private static final class WipingByteArrayOutputStream extends ByteArrayOutputStream {
        private byte[] copyAndWipe() {
            byte[] copy = toByteArray();
            wipe(buf);
            reset();
            return copy;
        }

        @Override
        public void close() {
            wipe(buf);
            reset();
        }
    }
}
