-- Búsqueda de productos: el LIKE '%texto%' recorría la tabla entera (dos veces, con el COUNT).
-- FULLTEXT para buscar por palabras y un índice para ordenar por cantidad de ofertas.
-- La sincronización semanal los suelta y los reconstruye en la tabla staging (SepaSnapshotService).
ALTER TABLE sepa_producto ADD FULLTEXT INDEX ft_sepa_producto_texto (descripcion, marca);
ALTER TABLE sepa_producto ADD INDEX idx_sepa_producto_ofertas (cantidad_ofertas);
