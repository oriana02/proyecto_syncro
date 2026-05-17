package com.syncro.pedido.security;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.syncro.pedido.model.Rol;
import com.syncro.pedido.model.Usuario;

import io.jsonwebtoken.JwtException;

@ExtendWith(MockitoExtension.class)
class JwtUtilTest {

    @InjectMocks
    private JwtUtil jwtUtil;

    private Usuario usuario;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(jwtUtil, "secret", "test-secret-key-for-jwt-token-generation-must-be-at-least-32-chars");
        ReflectionTestUtils.setField(jwtUtil, "expiration", 86400000L);

        usuario = Usuario.builder()
                .id(1L)
                .nombre("Test User")
                .email("test@test.com")
                .password("encodedPassword")
                .rol(Rol.OPERADOR)
                .activo(true)
                .build();
    }

    @Test
    void generateToken_Success() {
        String token = jwtUtil.generateToken(usuario);

        assertNotNull(token);
        assertFalse(token.isEmpty());
        assertTrue(token.split("\\.").length == 3); // JWT has 3 parts: header.payload.signature
    }

    @Test
    void extractUsername_Success() {
        String token = jwtUtil.generateToken(usuario);
        String username = jwtUtil.extractUsername(token);

        assertEquals("test@test.com", username);
    }

    @Test
    void extractExpiration_Success() {
        String token = jwtUtil.generateToken(usuario);
        Date expiration = jwtUtil.extractExpiration(token);

        assertNotNull(expiration);
        assertTrue(expiration.after(new Date()));
    }

    @Test
    void validateToken_ValidToken_ReturnsTrue() {
        String token = jwtUtil.generateToken(usuario);
        boolean isValid = jwtUtil.validateToken(token, usuario);

        assertTrue(isValid);
    }

    @Test
    void validateToken_ExpiredToken_ThrowsException() {
        ReflectionTestUtils.setField(jwtUtil, "expiration", -1000L); // Set expiration to past
        String token = jwtUtil.generateToken(usuario);

        assertThrows(io.jsonwebtoken.ExpiredJwtException.class, () -> {
            jwtUtil.validateToken(token, usuario);
        });
    }

    @Test
    void validateToken_WrongUser_ReturnsFalse() {
        String token = jwtUtil.generateToken(usuario);
        Usuario otherUser = Usuario.builder()
                .id(2L)
                .email("other@test.com")
                .rol(Rol.ADMIN)
                .activo(true)
                .build();

        boolean isValid = jwtUtil.validateToken(token, otherUser);

        assertFalse(isValid);
    }

    @Test
    void validateToken_ManipulatedToken_ThrowsException() {
        String token = jwtUtil.generateToken(usuario);
        String manipulatedToken = token + "tampered";

        assertThrows(JwtException.class, () -> {
            jwtUtil.extractUsername(manipulatedToken);
        });
    }

    @Test
    void getExpiration_ReturnsConfiguredValue() {
        Long expiration = jwtUtil.getExpiration();

        assertEquals(86400000L, expiration);
    }
}
