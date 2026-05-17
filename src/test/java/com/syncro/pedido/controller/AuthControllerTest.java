package com.syncro.pedido.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.syncro.pedido.dto.request.LoginRequest;
import com.syncro.pedido.dto.request.RegisterRequest;
import com.syncro.pedido.dto.response.AuthResponse;
import com.syncro.pedido.model.Rol;
import com.syncro.pedido.service.AuthService;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock
    private AuthService authService;

    @InjectMocks
    private AuthController authController;

    private LoginRequest loginRequest;
    private RegisterRequest registerRequest;
    private AuthResponse authResponse;

    @BeforeEach
    void setUp() {
        loginRequest = new LoginRequest("test@test.com", "password123");

        registerRequest = RegisterRequest.builder()
                .nombre("Test User")
                .email("test@test.com")
                .password("password123")
                .empresaId(1L)
                .rol(Rol.OPERADOR)
                .build();

        authResponse = AuthResponse.builder()
                .token("jwt-token")
                .tipo("Bearer")
                .email("test@test.com")
                .nombre("Test User")
                .rol("OPERADOR")
                .empresaId(1L)
                .expiresIn(86400000L)
                .build();
    }

    @Test
    void login_Success_Returns200() {
        when(authService.login(any(LoginRequest.class))).thenReturn(authResponse);

        ResponseEntity<AuthResponse> response = authController.login(loginRequest);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("jwt-token", response.getBody().getToken());
        assertEquals("Bearer", response.getBody().getTipo());
        assertEquals("test@test.com", response.getBody().getEmail());
    }

    @Test
    void register_Success_Returns201() {
        when(authService.register(any(RegisterRequest.class))).thenReturn(authResponse);

        ResponseEntity<AuthResponse> response = authController.register(registerRequest);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("jwt-token", response.getBody().getToken());
        assertEquals("Bearer", response.getBody().getTipo());
        assertEquals("test@test.com", response.getBody().getEmail());
    }

}
