package com.suresell.orders.multitenant;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Super-admin (KAM) global: login propio + validaciones cross-tenant (F3, Inc.3). */
class SuperAdminServiceTest {

    private static final String SECRET = "clave-de-pruebas-suficientemente-larga-32bytes!";
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    private SuperAdminService svc(SuperAdminRepository r, AuthService a) {
        // El JdbcTemplate solo lo usan los endpoints de sedes (RLS cross-tenant);
        // estos tests no los tocan, así que basta un mock.
        PlanRepository planRepo = org.mockito.Mockito.mock(PlanRepository.class);
        org.mockito.Mockito.lenient().when(planRepo.findAll()).thenReturn(java.util.List.of());
        return new SuperAdminService(r, a, planRepo, new PlanCatalogService(planRepo),
                org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class),
                // V48: el catálogo de flujos y el perfil solo los usan las sedes
                // y el alta; estos tests no los tocan.
                org.mockito.Mockito.mock(com.suresell.orders.flujo.FlujosDeVenta.class),
                org.mockito.Mockito.mock(PerfilDelNegocio.class),
                SECRET, 3600);
    }

    private boolean superClaim(String jwt) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(jwt).getPayload();
        return Boolean.TRUE.equals(c.get("super_admin", Boolean.class));
    }

    @Test
    void loginEmiteJwtConClaimSuperAdmin() {
        SuperAdminRepository r = mock(SuperAdminRepository.class);
        when(r.findByEmail("kam@x.co")).thenReturn(Optional.of(
                new SuperAdminRepository.SuperAdminRow(1, "kam@x.co", encoder.encode("clave123"))));

        SuperAdminService.LoginResponse res = svc(r, mock(AuthService.class)).login("kam@x.co", "clave123");

        assertTrue(superClaim(res.token()), "el JWT debe llevar super_admin=true");
    }

    @Test
    void loginClaveMalaEs401() {
        SuperAdminRepository r = mock(SuperAdminRepository.class);
        when(r.findByEmail(anyString())).thenReturn(Optional.of(
                new SuperAdminRepository.SuperAdminRow(1, "kam@x.co", encoder.encode("buena"))));
        AuthException ex = assertThrows(AuthException.class,
                () -> svc(r, mock(AuthService.class)).login("kam@x.co", "mala"));
        assertEquals(401, ex.status());
    }

    @Test
    void loginDesconocidoEs401() {
        SuperAdminRepository r = mock(SuperAdminRepository.class);
        when(r.findByEmail(anyString())).thenReturn(Optional.empty());
        AuthException ex = assertThrows(AuthException.class,
                () -> svc(r, mock(AuthService.class)).login("nadie@x.co", "x"));
        assertEquals(401, ex.status());
    }

    @Test
    void setPlanInvalidoEs400() {
        SuperAdminRepository r = mock(SuperAdminRepository.class);
        AuthException ex = assertThrows(AuthException.class,
                () -> svc(r, mock(AuthService.class)).setPlan("t1", "premium"));
        assertEquals(400, ex.status());
        verify(r, never()).updateTenantPlan(anyString(), anyString());
    }

    @Test
    void setPlanNegocioInexistenteEs404() {
        SuperAdminRepository r = mock(SuperAdminRepository.class);
        when(r.updateTenantPlan("nope", "pro")).thenReturn(0);
        AuthException ex = assertThrows(AuthException.class,
                () -> svc(r, mock(AuthService.class)).setPlan("nope", "pro"));
        assertEquals(404, ex.status());
    }
}
