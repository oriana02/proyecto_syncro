package com.syncro.pedido.service;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.syncro.pedido.dto.request.LoginRequest;
import com.syncro.pedido.dto.request.RegisterRequest;
import com.syncro.pedido.dto.response.AuthResponse;
import com.syncro.pedido.model.Empresa;
import com.syncro.pedido.model.Rol;
import com.syncro.pedido.model.Usuario;
import com.syncro.pedido.repository.EmpresaRepository;
import com.syncro.pedido.repository.UsuarioRepository;
import com.syncro.pedido.security.JwtUtil;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UsuarioRepository usuarioRepository;

    @Mock
    private EmpresaRepository empresaRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private AuthenticationManager authenticationManager;

    @InjectMocks
    private AuthService authService;

    private Empresa empresa;
    private Usuario usuario;
    private RegisterRequest registerRequest;
    private LoginRequest loginRequest;

    @BeforeEach
    void setUp() {
        empresa = Empresa.builder()
                .id(1L)
                .nombre("Test Empresa")
                .rut("12345678-9")
                .email("contacto@test.com")
                .activo(true)
                .build();

        usuario = Usuario.builder()
                .id(1L)
                .nombre("Test User")
                .email("test@test.com")
                .password("encodedPassword")
                .empresa(empresa)
                .rol(Rol.OPERADOR)
                .activo(true)
                .build();

        registerRequest = RegisterRequest.builder()
                .nombre("Test User")
                .email("test@test.com")
                .password("password123")
                .empresaId(1L)
                .rol(Rol.OPERADOR)
                .build();

        loginRequest = new LoginRequest("test@test.com", "password123");
    }

    @Test
    void register_Success() {
        when(usuarioRepository.existsByEmail(anyString())).thenReturn(false);
        when(empresaRepository.findById(1L)).thenReturn(Optional.of(empresa));
        when(passwordEncoder.encode(anyString())).thenReturn("encodedPassword");
        when(usuarioRepository.save(any(Usuario.class))).thenReturn(usuario);
        when(jwtUtil.generateToken(any(Usuario.class))).thenReturn("jwt-token");
        when(jwtUtil.getExpiration()).thenReturn(86400000L);

        AuthResponse response = authService.register(registerRequest);

        assertNotNull(response);
        assertEquals("jwt-token", response.getToken());
        assertEquals("Bearer", response.getTipo());
        assertEquals("test@test.com", response.getEmail());
        assertEquals("Test User", response.getNombre());
        assertEquals("OPERADOR", response.getRol());
        assertEquals(1L, response.getEmpresaId());
        assertEquals(86400000L, response.getExpiresIn());

        verify(usuarioRepository).existsByEmail("test@test.com");
        verify(empresaRepository).findById(1L);
        verify(passwordEncoder).encode("password123");
        verify(usuarioRepository).save(any(Usuario.class));
        verify(jwtUtil).generateToken(any(Usuario.class));
    }

    @Test
    void register_EmailAlreadyExists_ThrowsException() {
        when(usuarioRepository.existsByEmail(anyString())).thenReturn(true);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> authService.register(registerRequest)
        );

        assertEquals("Ya existe un usuario registrado con el email: test@test.com", exception.getMessage());
        verify(usuarioRepository).existsByEmail("test@test.com");
        verify(empresaRepository, never()).findById(anyLong());
        verify(usuarioRepository, never()).save(any(Usuario.class));
    }

    @Test
    void register_EmpresaNotFound_ThrowsException() {
        when(usuarioRepository.existsByEmail(anyString())).thenReturn(false);
        when(empresaRepository.findById(1L)).thenReturn(Optional.empty());

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> authService.register(registerRequest)
        );

        assertEquals("No existe empresa con ID: 1", exception.getMessage());
        verify(usuarioRepository).existsByEmail("test@test.com");
        verify(empresaRepository).findById(1L);
        verify(usuarioRepository, never()).save(any(Usuario.class));
    }

    @Test
    void register_DefaultRoleWhenNotProvided() {
        registerRequest.setRol(null);
        when(usuarioRepository.existsByEmail(anyString())).thenReturn(false);
        when(empresaRepository.findById(1L)).thenReturn(Optional.of(empresa));
        when(passwordEncoder.encode(anyString())).thenReturn("encodedPassword");
        when(usuarioRepository.save(any(Usuario.class))).thenReturn(usuario);
        when(jwtUtil.generateToken(any(Usuario.class))).thenReturn("jwt-token");
        when(jwtUtil.getExpiration()).thenReturn(86400000L);

        AuthResponse response = authService.register(registerRequest);

        assertNotNull(response);
        verify(usuarioRepository).save(argThat(u -> u.getRol() == Rol.OPERADOR));
    }

    @Test
    void login_Success() {
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(null);
        when(usuarioRepository.findByEmail(anyString())).thenReturn(Optional.of(usuario));
        when(jwtUtil.generateToken(any(Usuario.class))).thenReturn("jwt-token");
        when(jwtUtil.getExpiration()).thenReturn(86400000L);

        AuthResponse response = authService.login(loginRequest);

        assertNotNull(response);
        assertEquals("jwt-token", response.getToken());
        assertEquals("Bearer", response.getTipo());
        assertEquals("test@test.com", response.getEmail());
        assertEquals("Test User", response.getNombre());
        assertEquals("OPERADOR", response.getRol());
        assertEquals(1L, response.getEmpresaId());
        assertEquals(86400000L, response.getExpiresIn());

        verify(authenticationManager).authenticate(any(UsernamePasswordAuthenticationToken.class));
        verify(usuarioRepository).findByEmail("test@test.com");
        verify(jwtUtil).generateToken(any(Usuario.class));
    }

    @Test
    void login_BadCredentials_ThrowsException() {
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new org.springframework.security.authentication.BadCredentialsException("Bad credentials"));

        assertThrows(
                org.springframework.security.authentication.BadCredentialsException.class,
                () -> authService.login(loginRequest)
        );

        verify(authenticationManager).authenticate(any(UsernamePasswordAuthenticationToken.class));
        verify(usuarioRepository, never()).findByEmail(anyString());
        verify(jwtUtil, never()).generateToken(any(Usuario.class));
    }

    @Test
    void login_UserNotFoundAfterAuth_ThrowsException() {
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(null);
        when(usuarioRepository.findByEmail(anyString())).thenReturn(Optional.empty());

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> authService.login(loginRequest)
        );

        assertEquals("Usuario no encontrado", exception.getMessage());
        verify(authenticationManager).authenticate(any(UsernamePasswordAuthenticationToken.class));
        verify(usuarioRepository).findByEmail("test@test.com");
        verify(jwtUtil, never()).generateToken(any(Usuario.class));
    }
}
