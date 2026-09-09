package com.suresell.orders.multitenant;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Auth real: verifica login/registro/aislamiento del emisor de token, y (crítico)
 * que el hash BCrypt sembrado en V4__auth.sql valide con Spring — si no, el login
 * demo de staging quedaría roto. Sin DB: se mockea {@link AuthRepository}.
 */
class AuthServiceTest {

    private static final String SECRET = "clave-de-pruebas-suficientemente-larga-32bytes!";
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    private static final String REG_KEY = "KAM-SECRET";
    private static final String BIZ_KEY = "FISCAL-KEY";

    /**
     * N4 — los planes salen de BD; en estos tests no hay tabla, así que el
     * servicio cae a las constantes de {@link PlanCatalog}, que es exactamente
     * el comportamiento que tenía antes.
     */
    private PlanCatalogService planesDePrueba() {
        PlanRepository repo = org.mockito.Mockito.mock(PlanRepository.class);
        org.mockito.Mockito.lenient().when(repo.findAll()).thenReturn(java.util.List.of());
        return new PlanCatalogService(repo);
    }

    private AuthService newService(AuthRepository repo) {
        return new AuthService(repo, planesDePrueba(), SECRET, 3600, REG_KEY, BIZ_KEY);
    }

    /** Servicio con el registro DESHABILITADO (sin clave configurada). */
    private AuthService newServiceNoRegister(AuthRepository repo) {
        return new AuthService(repo, planesDePrueba(), SECRET, 3600, "", BIZ_KEY);
    }

    private String tenantOf(String jwt) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(jwt).getPayload();
        return c.get("tenant_id", String.class);
    }

    @SuppressWarnings("unchecked")
    private List<String> modulesOf(String jwt) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(jwt).getPayload();
        return (List<String>) c.get("modules", List.class);
    }

    private AuthRepository repoWithTenant(String plan) {
        AuthRepository repo = mock(AuthRepository.class);
        when(repo.buscarUsuarioParaLogin(anyString())).thenReturn(Optional.of(
                new AuthRepository.UsuarioParaLogin("u@x.co", "t1", encoder.encode("s3cret"),
                        "admin", true)));
        when(repo.findTenant("t1")).thenReturn(Optional.of(
                new AuthRepository.TenantRow("t1", "T", plan, "active", null, null, null, null)));
        return repo;
    }

    @Test
    void loginProReturnsModulesIncludingDescuentos_yEnElJwt() {
        AuthService.AuthResponse res = newService(repoWithTenant("pro")).login("u@x.co", "s3cret");
        assertTrue(res.modules().contains("descuentos"));
        assertTrue(res.modules().contains("ventas"));
        assertTrue(modulesOf(res.token()).contains("descuentos"), "el JWT trae el claim modules");
    }

    @Test
    void loginBasicoNoIncluyeDescuentos() {
        AuthService.AuthResponse res = newService(repoWithTenant("basico")).login("u@x.co", "s3cret");
        assertFalse(res.modules().contains("descuentos"));
        assertTrue(res.modules().contains("cierre"));
    }

    @Test
    void loginBasicoConOverrideGanaDescuentos() {
        AuthRepository repo = repoWithTenant("basico");
        when(repo.getOverrides("t1")).thenReturn(
                List.of(new AuthRepository.ModuleOverride("descuentos", true)));

        AuthService.AuthResponse res = newService(repo).login("u@x.co", "s3cret");

        assertTrue(res.modules().contains("descuentos"), "el override regala el módulo");
        assertTrue(modulesOf(res.token()).contains("descuentos"));
    }

    @Test
    void setModuleOverrideModuloDesconocidoEs400() {
        AuthRepository repo = mock(AuthRepository.class);
        AuthException ex = assertThrows(AuthException.class, () ->
                newService(repo).setModuleOverrides("t1", Map.of("xyz", true)));
        assertEquals(400, ex.status());
        verify(repo, never()).upsertOverride(any(), any(), anyBoolean());
    }

    @Test
    void seededDemoHashValidates() {
        // El hash exacto sembrado en V4__auth.sql para la clave 'shark2026'.
        String seeded = "$2a$10$lM1WJngu0T/FrD9PaW15QeR/PbGuZPXn7mRqrmChmQCupcfHw7jP.";
        assertTrue(encoder.matches("shark2026", seeded),
                "El hash sembrado debe validar con Spring BCrypt");
        assertFalse(encoder.matches("otra-clave", seeded));
    }

    @Test
    void loginHappyPathReturnsTenantScopedToken() {
        AuthRepository repo = mock(AuthRepository.class);
        String hash = encoder.encode("s3cret");
        when(repo.buscarUsuarioParaLogin("ana@shark.co")).thenReturn(Optional.of(
                new AuthRepository.UsuarioParaLogin("ana@shark.co", "shark-burger", hash,
                        "admin", true)));
        when(repo.findTenant("shark-burger")).thenReturn(Optional.of(
                new AuthRepository.TenantRow("shark-burger", "Shark Burger", "pro", "active", "NIT-1", "Calle 1", "3001", "Gracias")));

        AuthService.AuthResponse res = newService(repo).login("ana@shark.co", "s3cret");

        assertEquals("shark-burger", res.tenantId());
        assertEquals("Shark Burger", res.tenantName());
        assertEquals("pro", res.plan());
        assertEquals("shark-burger", tenantOf(res.token()));
    }

    @Test
    void loginWrongPasswordIs401AndGeneric() {
        AuthRepository repo = mock(AuthRepository.class);
        when(repo.buscarUsuarioParaLogin(anyString())).thenReturn(Optional.of(
                new AuthRepository.UsuarioParaLogin("ana@shark.co", "shark-burger", encoder.encode("right"),
                        "admin", true)));

        AuthException ex = assertThrows(AuthException.class,
                () -> newService(repo).login("ana@shark.co", "wrong"));
        assertEquals(401, ex.status());
        assertEquals("Credenciales inválidas", ex.getMessage());
    }

    @Test
    void loginUnknownEmailIs401SameMessage() {
        AuthRepository repo = mock(AuthRepository.class);
        when(repo.buscarUsuarioParaLogin(anyString())).thenReturn(Optional.empty());

        AuthException ex = assertThrows(AuthException.class,
                () -> newService(repo).login("nope@x.co", "whatever"));
        assertEquals(401, ex.status());
        assertEquals("Credenciales inválidas", ex.getMessage(),
                "No debe revelar si el email existe");
    }

    @Test
    void loginSuspendedTenantIs403() {
        AuthRepository repo = mock(AuthRepository.class);
        when(repo.buscarUsuarioParaLogin(anyString())).thenReturn(Optional.of(
                new AuthRepository.UsuarioParaLogin("ana@shark.co", "shark-burger", encoder.encode("s3cret"),
                        "admin", true)));
        when(repo.findTenant("shark-burger")).thenReturn(Optional.of(
                new AuthRepository.TenantRow("shark-burger", "Shark Burger", "pro", "suspended", null, null, null, null)));

        AuthException ex = assertThrows(AuthException.class,
                () -> newService(repo).login("ana@shark.co", "s3cret"));
        assertEquals(403, ex.status());
    }

    @Test
    void registerCreatesTenantAndAdminAndDerivesSlug() {
        AuthRepository repo = mock(AuthRepository.class);
        Map<String, String> insertedTenant = new HashMap<>();
        when(repo.emailExists(anyString())).thenReturn(false);
        when(repo.tenantExists("mi-negocio")).thenReturn(false);
        doAnswer(inv -> { insertedTenant.put("id", inv.getArgument(0));
                          insertedTenant.put("name", inv.getArgument(1));
                          return null; })
                .when(repo).insertTenant(anyString(), anyString(), anyString(), any(), any(), any());

        AuthService.AuthResponse res =
                newService(repo).register("¡Mi Negocio!", "owner@mn.co", "s3cret1", REG_KEY, null, null, null);

        assertEquals("mi-negocio", res.tenantId(), "slug limpio del nombre");
        assertEquals("mi-negocio", insertedTenant.get("id"));
        assertEquals("¡Mi Negocio!", insertedTenant.get("name"), "guarda el nombre original");
        assertEquals("mi-negocio", tenantOf(res.token()));
        verify(repo).insertUser(eq("owner@mn.co"), anyString(), eq("mi-negocio"), eq("admin"));
    }

    @Test
    void registerCollidingSlugGetsSuffix() {
        AuthRepository repo = mock(AuthRepository.class);
        when(repo.emailExists(anyString())).thenReturn(false);
        when(repo.tenantExists("shark-burger")).thenReturn(true);
        when(repo.tenantExists("shark-burger-2")).thenReturn(false);

        AuthService.AuthResponse res =
                newService(repo).register("Shark Burger", "b@x.co", "s3cret1", REG_KEY, null, null, null);

        assertEquals("shark-burger-2", res.tenantId());
    }

    @Test
    void registerDuplicateEmailIs409() {
        AuthRepository repo = mock(AuthRepository.class);
        when(repo.emailExists("dup@x.co")).thenReturn(true);

        AuthException ex = assertThrows(AuthException.class,
                () -> newService(repo).register("Neg", "dup@x.co", "s3cret1", REG_KEY, null, null, null));
        assertEquals(409, ex.status());
        verify(repo, never()).insertTenant(any(), any(), any(), any(), any(), any());
    }

    @Test
    void registerShortPasswordIs400() {
        AuthRepository repo = mock(AuthRepository.class);
        AuthException ex = assertThrows(AuthException.class,
                () -> newService(repo).register("Neg", "a@x.co", "123", REG_KEY, null, null, null));
        assertEquals(400, ex.status());
    }

    @Test
    void registerWrongKeyIs403AndCreatesNothing() {
        AuthRepository repo = mock(AuthRepository.class);
        AuthException ex = assertThrows(AuthException.class,
                () -> newService(repo).register("Neg", "a@x.co", "s3cret1", "clave-mala", null, null, null));
        assertEquals(403, ex.status());
        verify(repo, never()).insertTenant(any(), any(), any(), any(), any(), any());
        verify(repo, never()).insertUser(any(), any(), any(), any());
    }

    @Test
    void registerDisabledWhenNoKeyConfiguredIs403() {
        AuthRepository repo = mock(AuthRepository.class);
        AuthException ex = assertThrows(AuthException.class,
                () -> newServiceNoRegister(repo).register("Neg", "a@x.co", "s3cret1", "cualquiera", null, null, null));
        assertEquals(403, ex.status());
        verify(repo, never()).insertTenant(any(), any(), any(), any(), any(), any());
    }

    @Test
    void changePasswordHappyPathUpdatesHash() {
        AuthRepository repo = mock(AuthRepository.class);
        when(repo.findUserByEmail("ana@shark.co")).thenReturn(Optional.of(
                new AuthRepository.UserRow(1, "ana@shark.co", encoder.encode("vieja"),
                        "shark-burger", "admin", "active")));

        newService(repo).changePassword("ana@shark.co", "shark-burger", "vieja", "nueva123");

        verify(repo).updatePasswordHash(eq("ana@shark.co"), eq("shark-burger"), anyString());
    }

    @Test
    void changePasswordWrongCurrentIs401() {
        AuthRepository repo = mock(AuthRepository.class);
        when(repo.findUserByEmail(anyString())).thenReturn(Optional.of(
                new AuthRepository.UserRow(1, "ana@shark.co", encoder.encode("vieja"),
                        "shark-burger", "admin", "active")));

        AuthException ex = assertThrows(AuthException.class, () ->
                newService(repo).changePassword("ana@shark.co", "shark-burger", "otra", "nueva123"));
        assertEquals(401, ex.status());
        verify(repo, never()).updatePasswordHash(any(), any(), any());
    }

    @Test
    void changePasswordShortNewIs400() {
        AuthRepository repo = mock(AuthRepository.class);
        AuthException ex = assertThrows(AuthException.class, () ->
                newService(repo).changePassword("ana@shark.co", "shark-burger", "vieja", "123"));
        assertEquals(400, ex.status());
    }

    @Test
    void changePasswordTenantMismatchIs401() {
        AuthRepository repo = mock(AuthRepository.class);
        when(repo.findUserByEmail(anyString())).thenReturn(Optional.of(
                new AuthRepository.UserRow(1, "ana@shark.co", encoder.encode("vieja"),
                        "otro-tenant", "admin", "active")));

        AuthException ex = assertThrows(AuthException.class, () ->
                newService(repo).changePassword("ana@shark.co", "shark-burger", "vieja", "nueva123"));
        assertEquals(401, ex.status());
        verify(repo, never()).updatePasswordHash(any(), any(), any());
    }

    @Test
    void loginReturnsBusinessProfileForTicket() {
        AuthRepository repo = mock(AuthRepository.class);
        when(repo.buscarUsuarioParaLogin("ana@shark.co")).thenReturn(Optional.of(
                new AuthRepository.UsuarioParaLogin("ana@shark.co", "shark-burger",
                        encoder.encode("s3cret"), "admin", true)));
        when(repo.findTenant("shark-burger")).thenReturn(Optional.of(
                new AuthRepository.TenantRow("shark-burger", "Shark Burger", "pro", "active",
                        "NIT-1", "Calle 1", "3001", "Gracias")));

        AuthService.AuthResponse res = newService(repo).login("ana@shark.co", "s3cret");

        assertEquals("NIT-1", res.nit());
        assertEquals("Calle 1", res.address());
        assertEquals("3001", res.phone());
        assertEquals("Gracias", res.ticketFooter());
    }

    @Test
    void updateBusinessPersistsAndReturnsProfile() {
        AuthRepository repo = mock(AuthRepository.class);
        when(repo.findTenant("shark-burger")).thenReturn(Optional.of(
                new AuthRepository.TenantRow("shark-burger", "Nuevo Nombre", "pro", "active",
                        "NIT-9", "Nueva Dir", "555", "Vuelva pronto")));

        AuthService.BusinessProfile p = newService(repo).updateBusiness(
                "shark-burger", "Nuevo Nombre", "NIT-9", "Nueva Dir", "555", "Vuelva pronto", BIZ_KEY);

        verify(repo).updateBusinessProfile("shark-burger", "Nuevo Nombre", "NIT-9",
                "Nueva Dir", "555", "Vuelva pronto");
        assertEquals("Nuevo Nombre", p.name());
        assertEquals("NIT-9", p.nit());
    }

    @Test
    void updateBusinessBlankNameIs400() {
        AuthRepository repo = mock(AuthRepository.class);
        AuthException ex = assertThrows(AuthException.class, () ->
                newService(repo).updateBusiness("shark-burger", "  ", "n", "a", "p", "f", BIZ_KEY));
        assertEquals(400, ex.status());
        verify(repo, never()).updateBusinessProfile(any(), any(), any(), any(), any(), any());
    }

    @Test
    void updateBusinessWrongEditPasswordIs403() {
        AuthRepository repo = mock(AuthRepository.class);
        AuthException ex = assertThrows(AuthException.class, () ->
                newService(repo).updateBusiness("shark-burger", "Nombre", "n", "a", "p", "f", "mala"));
        assertEquals(403, ex.status());
        verify(repo, never()).updateBusinessProfile(any(), any(), any(), any(), any(), any());
    }

    @Test
    void updateBusinessDisabledWhenNoEditKeyIs403() {
        AuthRepository repo = mock(AuthRepository.class);
        // Servicio sin clave de edición configurada.
        AuthService svc = new AuthService(repo, planesDePrueba(), SECRET, 3600, REG_KEY, "");
        AuthException ex = assertThrows(AuthException.class, () ->
                svc.updateBusiness("shark-burger", "Nombre", "n", "a", "p", "f", "cualquiera"));
        assertEquals(403, ex.status());
        verify(repo, never()).updateBusinessProfile(any(), any(), any(), any(), any(), any());
    }

    @Test
    void createUserCajeroOk() {
        AuthRepository repo = mock(AuthRepository.class);
        when(repo.emailExists("caja@x.co")).thenReturn(false);
        when(repo.listUsers("t1")).thenReturn(
                List.of(new AuthRepository.UserSummary(2, "caja@x.co", "cajero", "active")));

        AuthRepository.UserSummary u = newService(repo).createUser("t1", "caja@x.co", "clave123", "cajero");

        assertEquals("cajero", u.role());
        verify(repo).insertUser(eq("caja@x.co"), anyString(), eq("t1"), eq("cajero"));
    }

    @Test
    void createUserSinRolPorDefectoEsCajero() {
        AuthRepository repo = mock(AuthRepository.class);
        when(repo.emailExists(anyString())).thenReturn(false);
        when(repo.listUsers("t1")).thenReturn(
                List.of(new AuthRepository.UserSummary(3, "x@x.co", "cajero", "active")));

        newService(repo).createUser("t1", "x@x.co", "clave123", null);

        verify(repo).insertUser(eq("x@x.co"), anyString(), eq("t1"), eq("cajero"));
    }

    @Test
    void createUserRolInvalidoEs400() {
        AuthRepository repo = mock(AuthRepository.class);
        AuthException ex = assertThrows(AuthException.class, () ->
                newService(repo).createUser("t1", "a@x.co", "clave123", "superadmin"));
        assertEquals(400, ex.status());
        verify(repo, never()).insertUser(any(), any(), any(), any());
    }

    @Test
    void createUserEmailDuplicadoEs409() {
        AuthRepository repo = mock(AuthRepository.class);
        when(repo.emailExists("dup@x.co")).thenReturn(true);
        AuthException ex = assertThrows(AuthException.class, () ->
                newService(repo).createUser("t1", "dup@x.co", "clave123", "cajero"));
        assertEquals(409, ex.status());
        verify(repo, never()).insertUser(any(), any(), any(), any());
    }

    @Test
    void forgotEmailDesconocidoNoInsertaPeroResponde() {
        AuthRepository repo = mock(AuthRepository.class);
        when(repo.buscarUsuarioParaLogin(anyString())).thenReturn(Optional.empty());

        AuthService.ForgotResponse r = newService(repo).forgotPassword("nadie@x.co");

        assertTrue(r.sent());
        assertNull(r.link(), "no revela nada");
        verify(repo, never()).insertReset(any(), any(), any(), any());
    }

    @Test
    void forgotEmailConocidoInsertaReset_yExponeLinkEnStaging() {
        AuthRepository repo = mock(AuthRepository.class);
        when(repo.buscarUsuarioParaLogin("ana@shark.co")).thenReturn(Optional.of(
                new AuthRepository.UsuarioParaLogin("ana@shark.co", "shark-burger", "h",
                        "admin", true)));
        AuthService svc = newService(repo);
        ReflectionTestUtils.setField(svc, "resetExposeLink", true);
        ReflectionTestUtils.setField(svc, "resetLinkBase", "https://pos.test");
        ReflectionTestUtils.setField(svc, "resetTtlMinutes", 60L);

        AuthService.ForgotResponse r = svc.forgotPassword("ana@shark.co");

        assertNotNull(r.link());
        assertTrue(r.link().startsWith("https://pos.test/reset?token="));
        verify(repo).insertReset(anyString(), eq("ana@shark.co"), eq("shark-burger"), any());
    }

    /** Un token válido, con las dos escrituras devolviendo "1 fila". */
    private AuthRepository repoConTokenValido() {
        AuthRepository repo = mock(AuthRepository.class);
        when(repo.buscarReset(anyString())).thenReturn(new AuthRepository.ConsultaDeReset(
                EstadoDelToken.valido, "ana@shark.co", "shark-burger"));
        when(repo.updatePasswordHash(any(), any(), any())).thenReturn(1);
        when(repo.markResetUsed(anyString())).thenReturn(1);
        return repo;
    }

    @Test
    void resetValidoActualizaClaveYMarcaUsado() {
        AuthRepository repo = repoConTokenValido();

        newService(repo).resetPassword("tok", "nueva123");

        verify(repo).updatePasswordHash(eq("ana@shark.co"), eq("shark-burger"), anyString());
        verify(repo).markResetUsed(anyString());
    }

    @Test
    void resetTokenInvalidoOExpiradoEs400() {
        // Los cuatro estados que no son `valido` dan el MISMO 400 y el mismo
        // mensaje. Esa ambigüedad de cara afuera es deliberada: distinguirlos en
        // la respuesta HTTP convertiría el endpoint en un oráculo de tokens.
        for (EstadoDelToken estado : new EstadoDelToken[] {
                EstadoDelToken.no_existe, EstadoDelToken.vencido, EstadoDelToken.usado }) {
            AuthRepository repo = mock(AuthRepository.class);
            when(repo.buscarReset(anyString())).thenReturn(
                    new AuthRepository.ConsultaDeReset(estado, null, null));

            AuthException ex = assertThrows(AuthException.class,
                    () -> newService(repo).resetPassword("tok", "nueva123"));

            assertEquals(400, ex.status(), "estado " + estado);
            assertEquals("Enlace inválido o expirado", ex.getMessage(),
                    "el mensaje al usuario NO puede delatar el estado: " + estado);
            verify(repo, never()).updatePasswordHash(any(), any(), any());
            verify(repo, never()).markResetUsed(any());
        }
    }

    @Test
    void siLaClaveNoLlegaACambiarseNoSeMarcaElToken() {
        // Un UPDATE que cambia cero filas no lanza nada: devuelve 0. Antes el
        // método respondía `ok` igual, así que el usuario creía tener una
        // contraseña nueva que nunca se guardó.
        AuthRepository repo = repoConTokenValido();
        when(repo.updatePasswordHash(any(), any(), any())).thenReturn(0);

        AuthException ex = assertThrows(AuthException.class,
                () -> newService(repo).resetPassword("tok", "nueva123"));

        assertEquals(500, ex.status());
        verify(repo, never()).markResetUsed(any());
    }

    @Test
    void siElTokenNoSePuedeMarcarLaOperacionFalla() {
        // El caso que motiva la transacción: si marcar el token falla, la
        // contraseña NO puede quedar cambiada — sería un enlace de un solo uso
        // que sirve dos veces. Aquí se comprueba que el método LANZA, que es lo
        // que hace revertir; que la reversión ocurra de verdad lo comprueba
        // ResetPasswordTransaccionalTest contra un Postgres real.
        AuthRepository repo = repoConTokenValido();
        when(repo.markResetUsed(anyString())).thenReturn(0);

        AuthException ex = assertThrows(AuthException.class,
                () -> newService(repo).resetPassword("tok", "nueva123"));

        assertEquals(500, ex.status());
    }

    @Test
    void resetClaveCortaEs400() {
        AuthRepository repo = mock(AuthRepository.class);
        AuthException ex = assertThrows(AuthException.class,
                () -> newService(repo).resetPassword("tok", "123"));
        assertEquals(400, ex.status());
    }

    // ----------------------------------------------------------------- ola 4

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("el KAM no puede pedir un reset para un correo de OTRO negocio, y el 404 no distingue")
    void kamNoRestableceCorreoDeOtroNegocio() {
        AuthRepository repo = mock(AuthRepository.class);
        when(repo.buscarUsuarioParaLogin("u@x.co")).thenReturn(Optional.of(
                new AuthRepository.UsuarioParaLogin("u@x.co", "otro", "hash", "admin", true)));

        AuthException ex = assertThrows(AuthException.class,
                () -> newService(repo).restablecerPorElKam("t1", "u@x.co", "kam@suresell.com.co"));
        org.assertj.core.api.Assertions.assertThat(ex.status()).isEqualTo(404);
        org.assertj.core.api.Assertions.assertThat(ex.getMessage()).doesNotContain("otro");
        org.mockito.Mockito.verify(repo, org.mockito.Mockito.never())
                .insertReset(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.any());
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("un cajero no es administrador: el KAM no le restablece la clave por aquí")
    void kamSoloRestableceAdministradores() {
        AuthRepository repo = mock(AuthRepository.class);
        when(repo.buscarUsuarioParaLogin("c@x.co")).thenReturn(Optional.of(
                new AuthRepository.UsuarioParaLogin("c@x.co", "t1", "hash", "cajero", true)));

        AuthException ex = assertThrows(AuthException.class,
                () -> newService(repo).restablecerPorElKam("t1", "c@x.co", "kam@suresell.com.co"));
        org.assertj.core.api.Assertions.assertThat(ex.status()).isEqualTo(404);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("sin proveedor de correo el KAM recibe la verdad: no se envió; el enlace solo si el entorno lo expone")
    void kamSinProveedorRecibeLaVerdad() throws Exception {
        AuthRepository repo = mock(AuthRepository.class);
        when(repo.buscarUsuarioParaLogin("a@x.co")).thenReturn(Optional.of(
                new AuthRepository.UsuarioParaLogin("a@x.co", "t1", "hash", "admin", true)));
        AuthService svc = newService(repo);
        // En pruebas los @Value de campo no se inyectan: el TTL sería 0.
        var ttl = AuthService.class.getDeclaredField("resetTtlMinutes");
        ttl.setAccessible(true);
        ttl.setLong(svc, 30);

        AuthService.EnvioDeReset sinExponer = svc.restablecerPorElKam("t1", "a@x.co", "kam@suresell.com.co");
        org.assertj.core.api.Assertions.assertThat(sinExponer.enviado()).isFalse();
        org.assertj.core.api.Assertions.assertThat(sinExponer.enlace()).isNull();
        org.mockito.Mockito.verify(repo).insertReset(anyString(), org.mockito.ArgumentMatchers.eq("a@x.co"),
                org.mockito.ArgumentMatchers.eq("t1"), org.mockito.ArgumentMatchers.any());

        var campo = AuthService.class.getDeclaredField("resetExposeLink");
        campo.setAccessible(true);
        campo.setBoolean(svc, true);
        AuthService.EnvioDeReset expuesto = svc.restablecerPorElKam("t1", "a@x.co", "kam@suresell.com.co");
        org.assertj.core.api.Assertions.assertThat(expuesto.enviado()).isFalse();
        org.assertj.core.api.Assertions.assertThat(expuesto.enlace()).isNotBlank();
        org.assertj.core.api.Assertions.assertThat(expuesto.expira()).isAfter(java.time.Instant.now());
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("el KAM lista usuarios con el negocio fijado en la transacción (RLS de users desde V39)")
    void listarUsuariosFijaElNegocio() {
        AuthRepository repo = mock(AuthRepository.class);
        AuthService svc = newService(repo);
        svc.listUsers("t1");
        svc.administradores("t1");
        org.mockito.Mockito.verify(repo, org.mockito.Mockito.times(2)).fijarNegocioEnLaTransaccion("t1");
    }
}
