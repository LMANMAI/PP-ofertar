-- Productos seguidos (MVP4): productos que el usuario sigue sin haberlos comprado; el job
-- diario (FollowedProductAlertService) avisa por push cuando entran en promo.
-- La clave única (user_id, ean) también sirve de índice para la FK y para listar por usuario.
CREATE TABLE `followed_products` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `ean` varchar(20) NOT NULL,
  `description` varchar(300) NOT NULL,
  `brand` varchar(255) DEFAULT NULL,
  `in_promo` bit(1) NOT NULL,
  `last_notified_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_followed_products_user_ean` (`user_id`,`ean`),
  CONSTRAINT `fk_followed_products_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
