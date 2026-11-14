-- Métricas para el tablero de observabilidad (PP-ofertar-dashboard).
--
-- metric_samples: una fila por serie y por minuto, ya agregada en memoria (MetricasSnapshotService
-- escribe un único lote por minuto, así el costo en la base no crece con el tráfico).
--   ts        inicio del minuto, epoch en segundos (UTC): sin zona horaria que interpretar.
--   name      nombre de la métrica (http.server.requests, jvm.memory.used, app.screen.views, ...).
--   tags      dimensiones de la serie, "clave=valor" ordenadas y separadas por "|".
--   kind      timer | counter | gauge.
--   cnt       timer/counter: eventos en el minuto. gauge: 1 (así SUM(total)/SUM(cnt) promedia).
--   total     timer: milisegundos acumulados. counter: lo mismo que cnt. gauge: el valor.
--   max_value timer: el más lento. gauge: el valor.
--   p50..p99  solo timers, en milisegundos.
CREATE TABLE `metric_samples` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `ts` bigint NOT NULL,
  `name` varchar(100) NOT NULL,
  `tags` varchar(255) NOT NULL,
  `kind` varchar(10) NOT NULL,
  `cnt` double NOT NULL,
  `total` double NOT NULL,
  `max_value` double NOT NULL,
  `p50` double DEFAULT NULL,
  `p95` double DEFAULT NULL,
  `p99` double DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_metric_samples_name_ts` (`name`,`ts`),
  KEY `idx_metric_samples_ts` (`ts`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Últimos errores que reportó la app (JS no capturado, promesa rechazada, contrato roto).
-- Sin usuario ni email: solo pantalla, plataforma y versión.
CREATE TABLE `app_error_events` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `ts` bigint NOT NULL,
  `kind` varchar(20) NOT NULL,
  `message` varchar(300) NOT NULL,
  `stack` varchar(2000) DEFAULT NULL,
  `screen` varchar(60) DEFAULT NULL,
  `platform` varchar(16) NOT NULL,
  `app_version` varchar(20) DEFAULT NULL,
  `os_version` varchar(20) DEFAULT NULL,
  `fatal` bit(1) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_app_error_events_ts` (`ts`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
