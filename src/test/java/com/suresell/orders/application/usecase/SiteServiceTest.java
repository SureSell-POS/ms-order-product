package com.suresell.orders.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.suresell.orders.domain.model.Site;
import com.suresell.orders.flujo.FlujosDeVenta;
import com.suresell.orders.infrastructure.persistence.SiteRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Flujo de venta por sede (V48; antes «modo de POS», Inc. 1 del modo Restaurante).
 *
 * <p>El catálogo se simula con las tres filas de V48. Lo que se comprueba aquí
 * es que el servicio NO decide nada por su cuenta: todo lo que responde sale
 * de esas filas.
 */
@ExtendWith(MockitoExtension.class)
class SiteServiceTest {

    static final FlujosDeVenta.Flujo DIRECTO =
            new FlujosDeVenta.Flujo("DIRECTO", "Venta directa", "", false, false, "PLAZOLETA", null, false, 1);
    static final FlujosDeVenta.Flujo MESA =
            new FlujosDeVenta.Flujo("MESA", "Cuenta por mesa", "", true, false, "RESTAURANTE", "RESTAURANTE", false, 2);
    static final FlujosDeVenta.Flujo RASTREADOR =
            new FlujosDeVenta.Flujo("RASTREADOR", "Rastreador", "", false, true, "PLAZOLETA", "PLAZOLETA", true, 3);

    @Mock
    private SiteRepository repository;
    @Mock
    private FlujosDeVenta flujos;

    private SiteService service;

    /** Un catálogo de mentira que se comporta como el de V48. */
    static void catalogo(FlujosDeVenta flujos) {
        List<FlujosDeVenta.Flujo> todos = List.of(DIRECTO, MESA, RASTREADOR);
        lenient().when(flujos.todos()).thenReturn(todos);
        lenient().when(flujos.defectoGlobal()).thenReturn(RASTREADOR);
        lenient().when(flujos.nombresValidos()).thenReturn("DIRECTO | MESA | RASTREADOR");
        lenient().when(flujos.porCodigo(anyString())).thenAnswer(i -> todos.stream()
                .filter(f -> f.codigo().equalsIgnoreCase(i.getArgument(0))).findFirst());
        lenient().when(flujos.resolver(anyString())).thenAnswer(i -> {
            String v = ((String) i.getArgument(0)).trim().toUpperCase();
            return todos.stream().filter(f -> f.codigo().equals(v) || v.equals(f.heredaDePosMode())).findFirst();
        });
        lenient().when(flujos.exigir(anyString())).thenAnswer(i -> {
            String v = ((String) i.getArgument(0)).trim().toUpperCase();
            return todos.stream().filter(f -> f.codigo().equals(v) || v.equals(f.heredaDePosMode())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Flujo de venta inválido: " + v));
        });
    }

    @BeforeEach
    void setUp() {
        catalogo(flujos);
        service = new SiteService(repository, flujos);
    }

    private Site sede(String flujo) {
        Site s = new Site();
        s.setId(1L);
        s.setName("Principal");
        s.setCode("PRINCIPAL");
        s.setFlujoDeVenta(flujo);
        s.setIsDefault(true);
        s.setActive(true);
        return s;
    }

    /**
     * Un negocio SIN sede configurada tiene que comportarse como siempre. Es la
     * garantía de que V48 no cambia nada para quien ya opera: el defecto sale
     * de la fila `es_defecto`, y esa fila es RASTREADOR.
     */
    @Test
    void sinSedeConfiguradaElModoEsElDeSiempre() {
        when(repository.findFirstByIsDefaultTrue()).thenReturn(Optional.empty());

        assertEquals("RASTREADOR", service.flujoEfectivo().codigo());
        assertEquals(Site.MODO_PLAZOLETA, service.modoEfectivo());
        assertFalse(service.enModoRestaurante());
    }

    @Test
    void elFlujoSaleDeLaSedePorDefecto() {
        when(repository.findFirstByIsDefaultTrue()).thenReturn(Optional.of(sede("MESA")));

        assertEquals("MESA", service.flujoEfectivo().codigo());
        // Lo que ve un POS viejo: el legado de la fila, no una constante.
        assertEquals(Site.MODO_RESTAURANTE, service.modoEfectivo());
        assertTrue(service.enModoRestaurante());
    }

    @Test
    @DisplayName("DIRECTO se reporta como PLAZOLETA a un lector viejo y no abre mesas")
    void directoSeReportaComoPlazoleta() {
        when(repository.findFirstByIsDefaultTrue()).thenReturn(Optional.of(sede("DIRECTO")));

        assertEquals(Site.MODO_PLAZOLETA, service.modoEfectivo());
        assertFalse(service.enModoRestaurante());
        assertFalse(service.flujoEfectivo().usaRastreador());
    }

    @Test
    @DisplayName("cambiar acepta un código del catálogo O un posMode viejo, y nada más")
    void cambiarModoResuelveContraElCatalogo() {
        when(repository.findById(1L)).thenReturn(Optional.of(sede("RASTREADOR")));
        when(repository.save(any(Site.class))).thenAnswer(i -> i.getArgument(0));

        Site s = service.cambiarModo(1L, "restaurante");
        assertEquals("MESA", s.getFlujoDeVenta());
        assertEquals(Site.MODO_RESTAURANTE, s.getPosMode());

        assertEquals("DIRECTO", service.cambiarModo(1L, "directo").getFlujoDeVenta());

        assertThrows(IllegalArgumentException.class, () -> service.cambiarModo(1L, "BUFFET"));
    }

    /** El código de la sede se normaliza: es la base de la numeración futura. */
    @Test
    void crearNormalizaElCodigoDeLaSede() {
        when(repository.findByCode("SEDE-CHICO")).thenReturn(Optional.empty());
        when(repository.findFirstByIsDefaultTrue()).thenReturn(Optional.of(sede("RASTREADOR")));
        when(repository.save(any(Site.class))).thenAnswer(i -> i.getArgument(0));

        Site creada = service.crear("Sede Chicó", "sede chicó", Site.MODO_RESTAURANTE);

        // Sin tildes y sin guiones colgando: este código va en la numeración.
        assertEquals("SEDE-CHICO", creada.getCode());
        assertEquals("MESA", creada.getFlujoDeVenta());
        assertEquals(Site.MODO_RESTAURANTE, creada.getPosMode());
        // No es la primera sede, así que no debe quedar como la de por defecto.
        assertFalse(creada.getIsDefault());
    }

    @Test
    @DisplayName("una sede nueva sin flujo hereda el de la sede principal, y un flujo inventado se rechaza")
    void crearSinFlujoHeredaElDeLaPrincipal() {
        when(repository.findByCode(anyString())).thenReturn(Optional.empty());
        when(repository.findFirstByIsDefaultTrue()).thenReturn(Optional.of(sede("DIRECTO")));
        when(repository.save(any(Site.class))).thenAnswer(i -> i.getArgument(0));

        assertEquals("DIRECTO", service.crear("Sede Norte", null, null).getFlujoDeVenta());

        // Antes, cualquier cosa que no fuera RESTAURANTE caía a PLAZOLETA en silencio.
        assertThrows(IllegalArgumentException.class, () -> service.crear("Sede Sur", null, "BUFFET"));
    }
}
