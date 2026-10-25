package com.matrix.payments.service;

import com.matrix.payments.config.GatewayProperties;
import com.matrix.payments.exception.InvalidSignatureException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** HMAC-SHA256 signing/verification of callback bodies (shared secret with the gateway). */
@Component
public class SignatureService {

    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] secret;

    public SignatureService(GatewayProperties props) {
        this.secret = props.callbackSecret().getBytes(StandardCharsets.UTF_8);
    }

    public String sign(String body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to compute HMAC", e);
        }
    }

    public void verify(String body, String provided) {
        if (provided == null) {
            throw new InvalidSignatureException();
        }
        byte[] expected = sign(body).getBytes(StandardCharsets.UTF_8);
        byte[] actual = provided.trim().getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) { // constant-time comparison
            throw new InvalidSignatureException();
        }
    }
}
