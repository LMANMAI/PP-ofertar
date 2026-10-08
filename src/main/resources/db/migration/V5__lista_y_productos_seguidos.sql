-- Lista de compras y productos seguidos (MVP4).
--
-- shopping_lists: hoy la app usa una sola lista por usuario (se crea al agregar el primer
-- producto), pero la tabla existe para poder tener varias con nombre sin migrar los ítems.
-- followed_products: productos que el usuario sigue sin haberlos comprado; el job diario
-- (FollowedProductAlertService) avisa por push cuando entran en promo.
CREATE TABLE `shopping_lists` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `name` varchar(100) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_shopping_lists_user` (`user_id`),
  CONSTRAINT `fk_shopping_lists_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `shopping_list_items` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `list_id` bigint NOT NULL,
  `ean` varchar(20) NOT NULL,
  `description` varchar(300) NOT NULL,
  `brand` varchar(255) DEFAULT NULL,
  `quantity` int NOT NULL,
  `checked` bit(1) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_shopping_list_items_list_ean` (`list_id`,`ean`),
  CONSTRAINT `fk_shopping_list_items_list` FOREIGN KEY (`list_id`) REFERENCES `shopping_lists` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

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
