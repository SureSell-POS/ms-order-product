-- =====================================================================
-- V49 -- `waiters.commission_percentage`: la columna que producción tenía y
--        ninguna migración creó.
--
-- EL SINTOMA (fase 0 / ola 4, 2026-09-09)
--
-- En staging, `GET /api/core/api/waiters` respondía 500 para CUALQUIER
-- negocio:
--
--     ERROR: column we1_0.commission_percentage does not exist
--
-- El panel lo llama al entrar (ventas por mesero del dashboard), así que un
-- restaurante nuevo veía «No se pudieron cargar las ventas por mesero» sin
-- haber hecho nada mal.
--
-- LA CAUSA
--
-- `ms-core-app` mapea `WaiterEntity.commissionPercentage` desde el commit
-- «Comisiones Meseros» (f7e1e16). La columna se añadió A MANO en producción
-- —está ahí, medido el 2026-09-09— y nunca pasó por Flyway, que es el único
-- que crea esquema (ver CLAUDE.md: «No crear tablas a mano: añadir una
-- migración»). Staging se construyó desde la cadena y no la tiene. Es el
-- mismo accidente de las 17 tablas de V28, en pequeño.
--
-- LO QUE HACE
--
-- `ADD COLUMN IF NOT EXISTS`: en producción no cambia nada (ya existe, con el
-- mismo tipo: numeric(5,2) nullable, como la mapea la entidad); en staging y
-- en cualquier base construida desde cero la crea. Sin datos, sin default: la
-- comisión es un dato que el administrador pone por mesero.
-- =====================================================================

SET lock_timeout = '3s';

ALTER TABLE waiters ADD COLUMN IF NOT EXISTS commission_percentage NUMERIC(5, 2);

COMMENT ON COLUMN waiters.commission_percentage IS
    'Porcentaje de comision negociado con el mesero (5.00 = 5 %). NULL = sin comision. La escribe ms-core-app.';
