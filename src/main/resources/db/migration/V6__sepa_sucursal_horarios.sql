-- Horario de atención de cada sucursal SEPA, como JSON (el mismo formato que HorariosResponse);
-- NULL si SEPA no lo informa. Las filas existentes quedan en NULL hasta la próxima sincronización.
-- La staging del snapshot se crea con CREATE TABLE ... LIKE sepa_sucursal, así que hereda la
-- columna y el RENAME TABLE la conserva; no lleva índice, no hay nada que reconstruir.
-- Condicional porque una base que corrió la rama de horarios con ddl-auto=update ya puede
-- tenerla (mismo tipo: TEXT NULL); MySQL 8 no tiene ADD COLUMN IF NOT EXISTS.
SET @existe := (SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sepa_sucursal' AND COLUMN_NAME = 'horarios');
SET @ddl := IF(@existe = 0, 'ALTER TABLE sepa_sucursal ADD COLUMN horarios TEXT NULL', 'DO 0');
PREPARE agregar_horarios FROM @ddl;
EXECUTE agregar_horarios;
DEALLOCATE PREPARE agregar_horarios;
