package com.suresell.orders.multitenant;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Un doble del esquema {@code inventario} para la suite de este servicio.
 *
 * <p>El catálogo de perfiles y el libro de asignaciones viven en la cadena de
 * migraciones de ms-smart-inventory (V50), que no está en este repositorio.
 * Este doble reproduce SOLO lo que {@code PerfilDelNegocio} toca —las columnas
 * y los CHECK que acotan lo que este servicio escribe— con los mismos nombres y
 * las mismas reglas de V50 (fuente cerrada, coherencia fuente/confianza, RLS
 * forzada). Si V50 cambia esas reglas, este doble tiene que cambiar con él; por
 * eso el nombre de cada constraint es el de V50, para que el diff lo encuentre.
 *
 * <p>Lo que NO reproduce: terminología, tema, {@code fn_rasgos_por_defecto},
 * las semillas completas. Nada de eso lo lee este servicio.
 */
final class EsquemaInventarioDePrueba {

    private EsquemaInventarioDePrueba() {}

    static void crear(JdbcTemplate jdbc) {
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS inventario");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS inventario.perfiles_verticales (
                codigo       TEXT   NOT NULL PRIMARY KEY,
                tenant_id    TEXT   NULL,
                nombre       TEXT   NOT NULL,
                descripcion  TEXT   NOT NULL DEFAULT '',
                capacidades  TEXT[] NOT NULL DEFAULT '{}',
                CONSTRAINT ck_perfiles_codigo CHECK (codigo ~ '^[a-z][a-z0-9_]{1,39}$')
            )""");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS inventario.perfil_asignado (
                id                UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
                secuencia         BIGINT      GENERATED ALWAYS AS IDENTITY,
                tenant_id         TEXT        NOT NULL
                                  DEFAULT NULLIF(current_setting('app.tenant_id', true), ''),
                perfil_codigo     TEXT        NOT NULL REFERENCES inventario.perfiles_verticales (codigo),
                usuario_id        TEXT        NOT NULL,
                fuente            TEXT        NOT NULL,
                referencia        TEXT        NULL,
                confianza         SMALLINT    NOT NULL,
                ocurrido_en       TIMESTAMPTZ NOT NULL,
                registrado_en     TIMESTAMPTZ NOT NULL DEFAULT now(),
                corrige_evento_id UUID        NULL,
                nota              TEXT        NULL,
                CONSTRAINT ck_perfil_asignado_usuario CHECK (length(btrim(usuario_id)) > 0),
                CONSTRAINT ck_perfil_asignado_fuente CHECK (fuente IN (
                    'migracion', 'declarado_comerciante', 'asignado_por_suresell')),
                CONSTRAINT ck_perfil_asignado_confianza CHECK (confianza BETWEEN 0 AND 3),
                CONSTRAINT ck_perfil_asignado_coherencia CHECK (
                    (fuente = 'asignado_por_suresell' AND confianza >= 2)
                    OR (fuente IN ('migracion', 'declarado_comerciante') AND confianza <= 1)),
                CONSTRAINT ck_perfil_asignado_reloj CHECK (ocurrido_en <= registrado_en)
            )""");
        jdbc.execute("""
            CREATE OR REPLACE VIEW inventario.v_perfil_vigente AS
            SELECT DISTINCT ON (a.tenant_id)
                a.tenant_id, p.codigo, p.nombre, p.descripcion, p.capacidades,
                a.registrado_en AS asignado_en, a.fuente, a.confianza, a.usuario_id AS asignado_por
            FROM inventario.perfil_asignado a
            JOIN inventario.perfiles_verticales p ON p.codigo = a.perfil_codigo
            ORDER BY a.tenant_id, a.secuencia DESC""");
        jdbc.execute("ALTER TABLE inventario.perfil_asignado ENABLE ROW LEVEL SECURITY");
        jdbc.execute("ALTER TABLE inventario.perfil_asignado FORCE ROW LEVEL SECURITY");
        jdbc.execute("DROP POLICY IF EXISTS tenant_isolation_perfil_asignado ON inventario.perfil_asignado");
        jdbc.execute("""
            CREATE POLICY tenant_isolation_perfil_asignado ON inventario.perfil_asignado
                USING (tenant_id = current_setting('app.tenant_id', true))
                WITH CHECK (tenant_id = current_setting('app.tenant_id', true))""");
        jdbc.update("""
            INSERT INTO inventario.perfiles_verticales (codigo, nombre, descripcion, capacidades) VALUES
              ('restaurante', 'Restaurante', 'Come aquí', ARRAY['componentes','vencimiento']),
              ('drogueria',   'Droguería',   'Vende medicamentos', ARRAY['componentes','lote','vencimiento','fefo','prescripcion']),
              ('minimercado', 'Minimercado', 'Vende al detal', ARRAY['lote','vencimiento','fefo','granel','variantes']),
              ('ferreteria',  'Ferretería',  'Vende herramienta', ARRAY['componentes','variantes','serial','granel']),
              ('optica',      'Óptica',      'Sin flujo declarado en public: aparece con la lista vacía', ARRAY['componentes','variantes','serial'])
            ON CONFLICT (codigo) DO NOTHING""");
    }
}
