-- Revocación de sesiones: los JWT emitidos antes de esta marca se rechazan.
ALTER TABLE users ADD COLUMN token_valid_from DATETIME(6) NULL;
