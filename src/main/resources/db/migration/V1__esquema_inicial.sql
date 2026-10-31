-- Esquema inicial: generado a partir de las entidades JPA (Hibernate sobre MySQL 8) el 2026-09-25.
-- En bases existentes Flyway lo marca como aplicado (baseline-on-migrate, baseline-version=1) y no lo ejecuta.
SET FOREIGN_KEY_CHECKS = 0;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `favorite_store_chains` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `chain_slug` varchar(50) NOT NULL,
  `chain_name` varchar(100) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKfuyrk1wquosls2i5l3y5ny011` (`user_id`,`chain_slug`),
  CONSTRAINT `FK2sr9kj2hgv9tp700do01lc8g2` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `password_reset_tokens` (
  `attempts` int NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `code_hash` varchar(255) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FKk3ndxg5xp6v7wd4gjyusp15gq` (`user_id`),
  CONSTRAINT `FKk3ndxg5xp6v7wd4gjyusp15gq` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `points_transactions` (
  `points` int NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `description` varchar(300) NOT NULL,
  `reason` enum('REDEEM','REFERRAL_ACTIVATED','REFERRAL_RETAINED','REFERRAL_SIGNUP') NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FKepyf6igu2l1nu69knrg7gklnk` (`user_id`),
  CONSTRAINT `FKepyf6igu2l1nu69knrg7gklnk` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `producto_imagen` (
  `intentos` int NOT NULL,
  `actualizado_at` datetime(6) NOT NULL,
  `ean` varchar(20) NOT NULL,
  `fuente` varchar(50) DEFAULT NULL,
  `url` varchar(1000) DEFAULT NULL,
  `estado` enum('ERROR','NOT_FOUND','OK') NOT NULL,
  PRIMARY KEY (`ean`),
  KEY `idx_producto_imagen_estado` (`estado`,`actualizado_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `push_tokens` (
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `last_seen_at` datetime(6) NOT NULL,
  `user_id` bigint NOT NULL,
  `platform` varchar(20) NOT NULL,
  `token` varchar(200) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK6cucwghehyeofnk02ys336v5f` (`token`),
  KEY `FKgisqbur2nbpemhidpyqv501nd` (`user_id`),
  CONSTRAINT `FKgisqbur2nbpemhidpyqv501nd` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `referrals` (
  `activated_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `referred_id` bigint NOT NULL,
  `referrer_id` bigint NOT NULL,
  `retained_at` datetime(6) DEFAULT NULL,
  `status` enum('ACTIVATED','PENDING','RETAINED') NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKjkhumgkh3v9jafyjn2k7p4txv` (`referred_id`),
  KEY `FK4moftyij76fw0oijnwc0jur2b` (`referrer_id`),
  CONSTRAINT `FK4moftyij76fw0oijnwc0jur2b` FOREIGN KEY (`referrer_id`) REFERENCES `users` (`id`),
  CONSTRAINT `FK6416wjicwfst2ch82idswdu7n` FOREIGN KEY (`referred_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `seen_chain_offers` (
  `first_seen_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `chain_slug` varchar(50) NOT NULL,
  `offer_id` varchar(200) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKres5175c3g4ye5c5c0su3nh0h` (`chain_slug`,`offer_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sepa_precio_comercio` (
  `cantidad_sucursales` int NOT NULL,
  `fecha_dataset` date NOT NULL,
  `precio_maximo` decimal(14,2) DEFAULT NULL,
  `precio_minimo` decimal(14,2) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `comercio_id` varchar(20) DEFAULT NULL,
  `ean` varchar(20) NOT NULL,
  `bandera` varchar(255) DEFAULT NULL,
  `razon_social` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_sepa_precio_comercio_ean` (`ean`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sepa_precio_grupo` (
  `cantidad_sucursales` int NOT NULL,
  `precio` decimal(14,2) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `comercio_id` varchar(20) DEFAULT NULL,
  `ean` varchar(20) NOT NULL,
  `sucursales` text NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_sepa_precio_grupo_ean` (`ean`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sepa_producto` (
  `cantidad_ofertas` int NOT NULL,
  `fecha_dataset` date NOT NULL,
  `precio_maximo` decimal(14,2) DEFAULT NULL,
  `precio_minimo` decimal(14,2) DEFAULT NULL,
  `precio_promedio` decimal(14,2) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `ean` varchar(20) NOT NULL,
  `descripcion` varchar(500) DEFAULT NULL,
  `marca` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_sepa_producto_ean` (`ean`),
  KEY `idx_sepa_producto_descripcion` (`descripcion`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sepa_sucursal` (
  `latitud` double NOT NULL,
  `longitud` double NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `bandera_id` varchar(20) DEFAULT NULL,
  `comercio_id` varchar(20) DEFAULT NULL,
  `provincia` varchar(20) DEFAULT NULL,
  `sucursal_id` varchar(40) DEFAULT NULL,
  `tipo` varchar(60) DEFAULT NULL,
  `localidad` varchar(120) DEFAULT NULL,
  `bandera` varchar(255) DEFAULT NULL,
  `direccion` varchar(255) DEFAULT NULL,
  `nombre` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_sepa_sucursal_ubicacion` (`latitud`,`longitud`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ticket_items` (
  `discount_amount` decimal(10,2) DEFAULT NULL,
  `original_price` decimal(10,2) DEFAULT NULL,
  `quantity` decimal(10,3) NOT NULL,
  `subtotal` decimal(10,2) DEFAULT NULL,
  `unit_price` decimal(10,2) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `ticket_id` bigint NOT NULL,
  `barcode` varchar(50) DEFAULT NULL,
  `category` varchar(50) DEFAULT NULL,
  `discount_description` varchar(200) DEFAULT NULL,
  `description` varchar(300) NOT NULL,
  `raw_description` varchar(300) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FK7ml4ferremfy360e45j4ud8x1` (`ticket_id`),
  CONSTRAINT `FK7ml4ferremfy360e45j4ud8x1` FOREIGN KEY (`ticket_id`) REFERENCES `tickets` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `tickets` (
  `reviewed` bit(1) NOT NULL,
  `subtotal` decimal(12,2) DEFAULT NULL,
  `total` decimal(12,2) DEFAULT NULL,
  `total_discounts` decimal(12,2) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `ticket_id` varchar(100) DEFAULT NULL,
  `store_name` varchar(200) DEFAULT NULL,
  `image_path` varchar(1000) DEFAULT NULL,
  `status` enum('FAILED','PENDING','PROCESSED') NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FK4eqsebpimnjen0q46ja6fl2hl` (`user_id`),
  CONSTRAINT `FK4eqsebpimnjen0q46ja6fl2hl` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `users` (
  `alternative_brands_enabled` bit(1) NOT NULL,
  `offers_push_enabled` bit(1) NOT NULL,
  `points` int NOT NULL,
  `store_search_radius_km` int NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `last_reactivation_nudge_at` datetime(6) DEFAULT NULL,
  `referral_code` varchar(12) DEFAULT NULL,
  `name` varchar(100) NOT NULL,
  `email` varchar(150) NOT NULL,
  `address` varchar(300) DEFAULT NULL,
  `password` varchar(255) NOT NULL,
  `profile_picture` longtext,
  `role` enum('ADMIN','USER') NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK6dotkott2kjsp8vw4d0m25fb7` (`email`),
  UNIQUE KEY `UKdb3w2sgtsy1kf0qitc77h0bpa` (`referral_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
SET FOREIGN_KEY_CHECKS = 1;
