package dev.hashmark.common.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtUtilTest {

    private JwtUtil jwtUtil;
    private final String secret = "thisisaverylongsecretkeythatneedstobeatleast256bitslong";

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil(secret, 900000, 604800000);
    }

    @Test
    void testGenerateAndValidateAccessToken() {
        Long userId = 1L;
        String token = jwtUtil.generateAccessToken(userId);
        
        assertNotNull(token);
        assertTrue(jwtUtil.validateToken(token));
        assertEquals(userId, jwtUtil.extractUserId(token));
    }

    @Test
    void testGenerateAndValidateRefreshToken() {
        Long userId = 2L;
        String token = jwtUtil.generateRefreshToken(userId);
        
        assertNotNull(token);
        assertTrue(jwtUtil.validateToken(token));
        assertEquals(userId, jwtUtil.extractUserId(token));
    }

    @Test
    void testValidateTokenWithInvalidToken() {
        assertFalse(jwtUtil.validateToken("invalid.token.here"));
    }
}
