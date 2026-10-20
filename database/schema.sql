-- ============================================================
--  CanalVideos · MariaDB schema (DDL canónico)
--  Aplicar desde la raíz del proyecto:
--    & "F:\MariaDB10.11\bin\mysql.exe" -u root -p < database/schema.sql
--  O importarlo con HeidiSQL (Archivo > Ejecutar archivo SQL)
-- ============================================================

CREATE DATABASE IF NOT EXISTS canal_videos_telegram
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE canal_videos_telegram;

-- ============================================================
-- TABLA: titulos
-- Caché de datos traídos de TheMovieDB + los que gestionas tú.
-- datos_json guarda la respuesta cruda de TMDB para no repetir
-- requests: la API solo se consulta si ultima_actualizacion es
-- vieja (TTL en el servicio).
-- ============================================================
CREATE TABLE IF NOT EXISTS titulos (
  id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
  tmdb_id              INT          NOT NULL UNIQUE,
  tipo                 ENUM('movie','tv') NOT NULL,
  titulo_original      VARCHAR(255) NOT NULL,
  titulo_latino        VARCHAR(255),             -- traducido es-MX
  sinopsis             TEXT,
  anio                 SMALLINT,                 -- año de estreno
  generos              VARCHAR(255),             -- "Drama, Thriller"
  rating               DECIMAL(3,1),             -- vote_average TMDB
  vote_count           INT,                      -- nº de votos (recibimiento)
  poster_path          VARCHAR(255),             -- ruta relativa TMDB (ej /abc.jpg)
  backdrop_path        VARCHAR(255),
  poster_local_path    VARCHAR(500),             -- copia descargada en tu PC
  ultima_actualizacion DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  datos_json           JSON,                     -- respuesta cruda de la API
  creado_en            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_titulo_latino (titulo_latino),
  INDEX idx_anio        (anio)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- TABLA: links_video
-- Botones del post: texto visible + URL, los pones tú a mano.
-- ============================================================
CREATE TABLE IF NOT EXISTS links_video (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  titulo_id         BIGINT       NULL,                 -- enlace de un título (película/serie)
  publicacion_id    BIGINT       NULL,                 -- enlace propio de un post personalizado
  texto_boton       VARCHAR(120) NOT NULL,             -- ej "4K 4.5Gb WEB-DL"
  url               TEXT         NOT NULL,
  terabox_filename  VARCHAR(255),                     -- nombre cifrado en Terabox (solo referencia tuya)
  orden             INT          NOT NULL DEFAULT 0,
  CONSTRAINT fk_links_titulo FOREIGN KEY (titulo_id)
    REFERENCES titulos(id) ON DELETE CASCADE,
  INDEX idx_links_titulo (titulo_id),
  INDEX idx_links_publicacion (publicacion_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- TABLA: acoplamientos
-- Conjuntos de canales/grupos. Un acoplamiento agrupa N canales
-- para publicar el mismo post en todos desde una sola accion.
-- Va ANTES de publicaciones y cola_publicaciones: ambas la
-- referencian con FK, y las FK exigen que la tabla ya exista.
-- ============================================================
CREATE TABLE IF NOT EXISTS acoplamientos (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  nombre      VARCHAR(100) NOT NULL,
  activo      TINYINT(1) NOT NULL DEFAULT 1,
  creado_en   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- TABLA: publicaciones
-- Borradores de post y registro de lo ya publicado en Telegram.
-- ============================================================
CREATE TABLE IF NOT EXISTS publicaciones (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  titulo_id         BIGINT      NULL,            -- NULL en posts personalizados (sin TMDB)
  tipo              VARCHAR(16) NOT NULL DEFAULT 'titulo',  -- 'titulo' | 'personalizado'
  caption           TEXT,                        -- texto final del post
  estado            ENUM('borrador','publicado') NOT NULL DEFAULT 'borrador',
  modo              VARCHAR(8),                    -- 'real' | 'test' (NULL = borrador compartido)
  acoplamiento_id   BIGINT,                        -- acoplamiento usado al publicar
  telegram_message_id BIGINT,                    -- id del msg cuando se publique
  telegram_chat_id  VARCHAR(64),                 -- canal o grupo donde se publicó
  fecha_publicacion DATETIME,
  creado_en         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  fecha_editado     DATETIME,                    -- cuándo se editó el post en Telegram
  fecha_republicado DATETIME,                    -- cuándo se volvió a publicar
  telegram_grupo_message_id BIGINT,              -- id del msg en el grupo de discusión
  chat_dedicado     TINYINT(1) DEFAULT 1,        -- si el post se envió a un chat dedicado
  CONSTRAINT fk_pub_titulo FOREIGN KEY (titulo_id)
    REFERENCES titulos(id) ON DELETE CASCADE,
  CONSTRAINT fk_pub_acoplamiento FOREIGN KEY (acoplamiento_id)
    REFERENCES acoplamientos(id) ON DELETE SET NULL,
  INDEX idx_pub_estado (estado),
  INDEX idx_pub_tipo   (tipo)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- TABLA: publicacion_media
-- Imágenes de un post (posts personalizados y, a futuro, por título).
-- La app guarda una COPIA PROCESADA (comprimida/redimensionada) en
-- storage/media y aquí solo la referencia. origen: 'subida' | 'tmdb'.
-- tmdb_path conserva la ruta original de TMDB (trazabilidad/reenvío).
-- ============================================================
CREATE TABLE IF NOT EXISTS publicacion_media (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  publicacion_id BIGINT       NOT NULL,
  orden          INT          NOT NULL DEFAULT 0,
  archivo        VARCHAR(500),                   -- ruta relativa dentro de storage/media
  origen         VARCHAR(12)  NOT NULL DEFAULT 'subida',  -- 'subida' | 'tmdb'
  tmdb_tipo      VARCHAR(8),                     -- 'movie' | 'tv' (si vino de TMDB)
  tmdb_path      VARCHAR(255),                   -- file_path de TMDB (ej /abc.jpg)
  ancho          INT,
  alto           INT,
  bytes          BIGINT,
  creado_en      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_media_pub FOREIGN KEY (publicacion_id)
    REFERENCES publicaciones(id) ON DELETE CASCADE,
  INDEX idx_media_pub (publicacion_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- TABLA: cola_publicaciones
-- Cola de publicaciones programadas: el bot publica solo el borrador
-- cuando llega su hora. Guarda el modo en que se programó (real/test)
-- para publicar siempre al destino elegido aunque luego se cambie el modo.
-- Estados: pendiente -> publicando -> publicado | fallo | cancelado.
-- ============================================================
CREATE TABLE IF NOT EXISTS cola_publicaciones (
  id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
  publicacion_id     BIGINT       NOT NULL,
  programado_para    DATETIME     NOT NULL,
  estado             ENUM('pendiente','publicando','publicado','fallo','cancelado') NOT NULL DEFAULT 'pendiente',
  modo               VARCHAR(8)   NOT NULL,                   -- 'real' | 'test'
  acoplamiento_id    BIGINT,                                  -- acoplamiento destino al programar
  error              TEXT,                                    -- último error si falló
  publicado_en       DATETIME,
  telegram_message_id BIGINT,
  ultimo_intento     DATETIME,
  creado_en          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_cola_pub FOREIGN KEY (publicacion_id)
    REFERENCES publicaciones(id) ON DELETE CASCADE,
  CONSTRAINT fk_cola_acoplamiento FOREIGN KEY (acoplamiento_id)
    REFERENCES acoplamientos(id) ON DELETE SET NULL,
  INDEX idx_cola_estado (estado),
  INDEX idx_cola_prog (programado_para)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- TABLA: comentarios
-- Comentarios leídos del grupo de discusión vinculado al canal.
-- Cada comentario es un reply a un post del canal (telegram_message_id
-- del canal = reply_to_message.message_id del comentario).
-- ============================================================
CREATE TABLE IF NOT EXISTS comentarios (
  id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
  publicacion_id       BIGINT       NOT NULL,
  telegram_message_id  BIGINT       NOT NULL UNIQUE,
  autor                VARCHAR(255),
  texto                TEXT,
  fecha                DATETIME     NOT NULL,
  creado_en            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_comentario_pub FOREIGN KEY (publicacion_id)
    REFERENCES publicaciones(id) ON DELETE CASCADE,
  INDEX idx_comentario_pub (publicacion_id),
  INDEX idx_comentario_fecha (fecha)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
-- ============================================================
-- TABLA: config_app
-- Unicoc registro (id=1) con la configuracion editable de la app.
-- TODOS los valores se guardan CIFRADOS (AES-256-GCM): en la tabla
-- no hay texto plano (ni token, ni canales, ni numeros). El IV y el
-- tag viajan dentro de cada blob: [0x01][IV 12B][ciphertext+tag].
-- Seed al arrancar: si esta vacia, ConfigService la llena con los
-- valores de application.yaml (cifrados) sin cambiar el comportamiento.
-- ============================================================
CREATE TABLE IF NOT EXISTS config_app (
  id                     TINYINT UNSIGNED NOT NULL PRIMARY KEY,  -- siempre 1
  bot_token_cifrado      VARBINARY(1024),
  canal_principal_cifrado VARBINARY(512),
  canal_prueba_cifrado   VARBINARY(512),
  tmdb_token_cifrado     VARBINARY(512),
  limite_caption_cifrado VARBINARY(128),
  intervalo_cola_cifrado VARBINARY(128),
  colgada_min_cifrado    VARBINARY(128),
  creado_en              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  actualizado_en         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- TABLA: bots
-- Bots de Telegram. El token se guarda CIFRADO (AES-256-GCM), igual
-- que en config_app. Cada canal puede pertenecer a un bot (bot_id).
-- ============================================================
CREATE TABLE IF NOT EXISTS bots (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  nombre         VARCHAR(100) NOT NULL,
  token_cifrado  VARBINARY(1024) NOT NULL,
  activo         TINYINT(1) NOT NULL DEFAULT 1,
  creado_en      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  telegram_id    BIGINT,
  username       VARCHAR(64)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- TABLA: canales
-- Canales y grupos de Telegram. Cada uno tiene nombre legible,
-- chat_id (@nombre o id numerico), tipo (real/test) y bot opcional.
-- ============================================================
CREATE TABLE IF NOT EXISTS canales (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  nombre      VARCHAR(100) NOT NULL,
  chat_id     VARCHAR(64) NOT NULL,
  bot_id      BIGINT NULL,
  tipo        ENUM('real','test') NOT NULL DEFAULT 'real',
  activo      TINYINT(1) NOT NULL DEFAULT 1,
  creado_en   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  chat_tipo   VARCHAR(16) NULL,
  UNIQUE KEY uk_canales_chat (chat_id, tipo),
  INDEX fk_canales_bot (bot_id),
  CONSTRAINT fk_canales_bot FOREIGN KEY (bot_id)
    REFERENCES bots(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- TABLA: acoplamiento_canales
-- Relacion N:N entre acoplamientos y canales. Un canal puede
-- pertenecer a mas de un acoplamiento.
-- ============================================================
CREATE TABLE IF NOT EXISTS acoplamiento_canales (
  acoplamiento_id BIGINT NOT NULL,
  canal_id        BIGINT NOT NULL,
  PRIMARY KEY (acoplamiento_id, canal_id),
  CONSTRAINT fk_acop_canal FOREIGN KEY (acoplamiento_id)
    REFERENCES acoplamientos(id) ON DELETE CASCADE,
  CONSTRAINT fk_acop_canal_canal FOREIGN KEY (canal_id)
    REFERENCES canales(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- TABLA: respaldos
-- Historial de respaldos (automáticos/manuales) y de las operaciones
-- destructivas (restauración / importación). Cada fila nace en
-- 'en_progreso'; si la app muere a mitad (apagón) queda 'en_progreso'
-- y al siguiente arranque se marca 'interrumpido' (integridad).
-- Ver database/migracion_respaldos.sql (mismo DDL, idempotente).
-- ============================================================
CREATE TABLE IF NOT EXISTS respaldos (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  tipo            ENUM('automatico','manual') NOT NULL,
  estado          ENUM('en_progreso','completado','fallo','interrumpido') NOT NULL DEFAULT 'en_progreso',
  motivo          VARCHAR(40)  NOT NULL DEFAULT 'manual',
  archivo         VARCHAR(255) NOT NULL,
  ruta            VARCHAR(1000) NOT NULL,
  tamano_bytes    BIGINT,
  checksum_sha256 CHAR(64),
  motor           VARCHAR(40)  NOT NULL DEFAULT 'mariadb',
  version         VARCHAR(40),
  mensaje         TEXT,
  iniciado_en     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  terminado_en    DATETIME,
  duracion_ms     BIGINT,
  verificado_en   DATETIME,
  token_ejecucion CHAR(36) NOT NULL,
  INDEX idx_resp_estado   (estado),
  INDEX idx_resp_tipo     (tipo),
  INDEX idx_resp_iniciado (iniciado_en)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- TABLA: config_respaldo
-- Un único registro (id=1) con la configuración de respaldos, cifrada
-- (AES-256-GCM) igual que config_app.
-- ============================================================
CREATE TABLE IF NOT EXISTS config_respaldo (
  id                      TINYINT UNSIGNED NOT NULL PRIMARY KEY,
  auto_activo_cifrado     VARBINARY(128),
  modo_cifrado            VARBINARY(128),
  intervalo_horas_cifrado VARBINARY(128),
  hora_diaria_cifrado     VARBINARY(128),
  dir_auto_cifrado        VARBINARY(4096),
  dir_manual_cifrado      VARBINARY(4096),
  bin_dir_cifrado         VARBINARY(4096),
  retencion_cifrado       VARBINARY(128),
  creado_en               DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  actualizado_en          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- TABLA: caption_templates
-- Plantillas de texto reutilizables para los posts.
-- ============================================================
CREATE TABLE IF NOT EXISTS caption_templates (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  name       VARCHAR(100) NOT NULL UNIQUE,
  template   TEXT NOT NULL,
  is_default TINYINT(1) NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- TABLA: settings
-- Ajustes simples clave/valor (no cifrados).
-- ============================================================
CREATE TABLE IF NOT EXISTS settings (
  clave          VARCHAR(64) NOT NULL PRIMARY KEY,
  valor          VARCHAR(255),
  actualizado_en DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ============================================================
-- TABLA: sugerencias
-- Candidatos guardados desde TMDB (descubrir/guardar para después).
-- ============================================================
CREATE TABLE IF NOT EXISTS sugerencias (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  tmdb_id         INT NOT NULL,
  media_type      VARCHAR(10) NOT NULL,
  titulo          VARCHAR(255) NOT NULL,
  anio            VARCHAR(4),
  poster_path     VARCHAR(255),
  origen          VARCHAR(10) NOT NULL DEFAULT 'guardado',
  cargado_en      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  titulo_original VARCHAR(255),
  UNIQUE KEY uq_sug_tmdb (tmdb_id, media_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
