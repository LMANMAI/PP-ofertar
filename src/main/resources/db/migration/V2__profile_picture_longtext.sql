-- ddl-auto=update nunca modifica el tipo de una columna existente: en las bases
-- creadas antes de este cambio profile_picture quedó como VARCHAR(255).
ALTER TABLE users MODIFY COLUMN profile_picture LONGTEXT NULL;
