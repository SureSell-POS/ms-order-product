package com.suresell.orders.infrastructure.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.flyway.FlywayProperties;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * La segunda cadena Flyway de este servicio: esquemas {@code pedidos} y {@code red}
 * (plan de mayoristas F0.9, plan de la red B2B T1.1).
 *
 * <h2>🔴 Por qué NO es un {@code @Bean Flyway}</h2>
 *
 * En Spring Boot 3.4.1 la configuración automática de Flyway lleva
 * {@code @ConditionalOnMissingBean(Flyway.class)}. Declarar un bean de tipo
 * {@link Flyway} para esta cadena haría desaparecer el de {@code public}: las
 * migraciones de ventas dejarían de aplicarse al arrancar, sin excepción y sin
 * una línea en el log. Por eso aquí se construye y se ejecuta dentro de un
 * {@link InitializingBean}, y ningún bean del contexto es de tipo {@code Flyway}.
 *
 * <h2>Orden de arranque</h2>
 *
 * {@code @DependsOn("flywayInitializer")}: esta cadena corre después de
 * {@code public}, porque sus tablas referencian {@code public.tenants},
 * {@code public.users} y {@code public.clientes}. Hoy no hay nada más que
 * ordenar ({@code ddl-auto: none} en cloud, así que Hibernate no valida el
 * esquema al arrancar).
 *
 * <p><b>Para quien añada código sobre {@code pedidos} o {@code red}:</b> todo
 * repositorio, planificador o consulta de arranque que toque esos esquemas
 * tiene que declarar {@code @DependsOn("flywayPedidos")}. Si no, puede correr
 * antes de que existan sus tablas. {@code HistorialesSeparadosTest} hace una
 * consulta a {@code red} al levantar el contexto.
 *
 * <h2>Configuración</h2>
 *
 * Misma conexión privilegiada que migra {@code public} ({@code spring.flyway.url},
 * {@code user}, {@code password}). Historial propio. {@code createSchemas} queda
 * en su valor por defecto (true): con {@code false} Flyway 10.20.1 no arranca,
 * porque crea la tabla de historial en {@code pedidos} antes de ejecutar la V1
 * (medido el 2026-09-13: «schema "pedidos" does not exist»). La V1 verifica
 * los esquemas y da los permisos. Solo en {@code cloud} y con
 * {@code pedidos.flyway.enabled=true}; en local el servicio usa SQLite.
 */
@Component("flywayPedidos")
@Profile("cloud")
@ConditionalOnProperty(prefix = "pedidos.flyway", name = "enabled", havingValue = "true")
@DependsOn("flywayInitializer")
public class FlywayPedidos implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(FlywayPedidos.class);

    public static final String TABLA_DE_HISTORIAL = "flyway_schema_history_pedidos";
    public static final String UBICACION = "classpath:db/migration-pedidos";

    private final FlywayProperties principal;

    public FlywayPedidos(FlywayProperties principal) {
        this.principal = principal;
    }

    /**
     * La configuración de la cadena, en un solo sitio: la usan el arranque y las
     * pruebas que migran sin levantar Spring. Una copia en cada prueba es como
     * se desincronizan.
     */
    public static FluentConfiguration configuracion(String url, String usuario, String clave) {
        return Flyway.configure()
                .dataSource(url, usuario, clave)
                .schemas("pedidos", "red")
                .defaultSchema("pedidos")
                .table(TABLA_DE_HISTORIAL)
                .locations(UBICACION)
                .cleanDisabled(true)
                .validateOnMigrate(true)
                .outOfOrder(false);
    }

    @Override
    public void afterPropertiesSet() {
        if (principal.getUrl() == null || principal.getUser() == null) {
            throw new IllegalStateException("La cadena `pedidos` migra con la conexión privilegiada de "
                    + "spring.flyway (url y user) y no está configurada. No se usa la del pool de la app.");
        }
        MigrateResult r = configuracion(principal.getUrl(), principal.getUser(), principal.getPassword())
                .load().migrate();
        log.info("Cadena pedidos: {} migraciones aplicadas; version {} (historial pedidos.{})",
                r.migrationsExecuted, r.targetSchemaVersion, TABLA_DE_HISTORIAL);
    }
}
