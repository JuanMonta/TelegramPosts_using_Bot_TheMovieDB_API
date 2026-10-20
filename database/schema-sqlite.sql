-- ============================================================
--  CanalVideos · esquema SQLite (DDL canónico)
--  Equivalente a database/schema.sql para el motor SQLite (archivo).
--  La app lo aplica sola al crear la base de datos; también se puede
--  ejecutar a mano con: sqlite3 canal_videos_telegram.db < schema-sqlite.sql
-- ============================================================

CREATE TABLE IF NOT EXISTS titulos (
  id                   INTEGER PRIMARY KEY AUTOINCREMENT,
  tmdb_id              INTEGER NOT NULL UNIQUE,
  tipo                 TEXT    NOT NULL CHECK (tipo IN ('movie','tv')),
  titulo_original      TEXT    NOT NULL,
  titulo_latino        TEXT,
  sinopsis             TEXT,
  anio                 INTEGER,
  generos              TEXT,
  rating               REAL,
  vote_count           INTEGER,
  poster_path          TEXT,
  backdrop_path        TEXT,
  poster_local_path    TEXT,
  ultima_actualizacion TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f','now','localtime')),
  datos_json           TEXT,
  creado_en            TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f','now','localtime'))
);

CREATE INDEX IF NOT EXISTS idx_titulo_latino ON titulos (titulo_latino);
CREATE INDEX IF NOT EXISTS idx_anio          ON titulos (anio);

CREATE TABLE IF NOT EXISTS links_video (
  id                INTEGER PRIMARY KEY AUTOINCREMENT,
  titulo_id         INTEGER REFERENCES titulos (id) ON DELETE CASCADE,       -- enlace de un título
  publicacion_id    INTEGER,                                                 -- enlace de un post personalizado
  texto_boton       TEXT    NOT NULL,
  url               TEXT    NOT NULL,
  terabox_filename  TEXT,
  orden             INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_links_titulo      ON links_video (titulo_id);
CREATE INDEX IF NOT EXISTS idx_links_publicacion ON links_video (publicacion_id);

CREATE TABLE IF NOT EXISTS bots (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  nombre        TEXT    NOT NULL,
  token_cifrado BLOB    NOT NULL,
  activo        INTEGER NOT NULL DEFAULT 1,
  creado_en     TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f','now','localtime')),
  telegram_id   INTEGER,
  username      TEXT
);

CREATE TABLE IF NOT EXISTS acoplamientos (
  id        INTEGER PRIMARY KEY AUTOINCREMENT,
  nombre    TEXT    NOT NULL,
  activo    INTEGER NOT NULL DEFAULT 1,
  creado_en TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f','now','localtime'))
);

CREATE TABLE IF NOT EXISTS publicaciones (
  id                       INTEGER PRIMARY KEY AUTOINCREMENT,
  titulo_id                INTEGER REFERENCES titulos (id) ON DELETE CASCADE,  -- NULL en posts personalizados
  tipo                     TEXT    NOT NULL DEFAULT 'titulo',                  -- 'titulo' | 'personalizado'
  caption                  TEXT,
  estado                   TEXT    NOT NULL DEFAULT 'borrador' CHECK (estado IN ('borrador','publicado')),
  modo                     TEXT,
  acoplamiento_id          INTEGER REFERENCES acoplamientos (id) ON DELETE SET NULL,
  telegram_message_id      INTEGER,
  telegram_chat_id         TEXT,
  fecha_publicacion        TEXT,
  creado_en                TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f','now','localtime')),
  fecha_editado            TEXT,
  fecha_republicado        TEXT,
  telegram_grupo_message_id INTEGER,
  chat_dedicado            INTEGER DEFAULT 1
);

CREATE INDEX IF NOT EXISTS idx_pub_estado ON publicaciones (estado);
CREATE INDEX IF NOT EXISTS idx_pub_modo   ON publicaciones (modo);
CREATE INDEX IF NOT EXISTS idx_pub_tipo   ON publicaciones (tipo);

-- Imágenes de un post: la app guarda una copia procesada en storage/media.
CREATE TABLE IF NOT EXISTS publicacion_media (
  id             INTEGER PRIMARY KEY AUTOINCREMENT,
  publicacion_id INTEGER NOT NULL REFERENCES publicaciones (id) ON DELETE CASCADE,
  orden          INTEGER NOT NULL DEFAULT 0,
  archivo        TEXT,
  origen         TEXT    NOT NULL DEFAULT 'subida',
  tmdb_tipo      TEXT,
  tmdb_path      TEXT,
  ancho          INTEGER,
  alto           INTEGER,
  bytes          INTEGER,
  creado_en      TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f','now','localtime'))
);

CREATE INDEX IF NOT EXISTS idx_media_pub ON publicacion_media (publicacion_id);

CREATE TABLE IF NOT EXISTS cola_publicaciones (
  id                  INTEGER PRIMARY KEY AUTOINCREMENT,
  publicacion_id      INTEGER NOT NULL REFERENCES publicaciones (id) ON DELETE CASCADE,
  programado_para     TEXT    NOT NULL,
  estado              TEXT    NOT NULL DEFAULT 'pendiente'
                        CHECK (estado IN ('pendiente','publicando','publicado','fallo','cancelado')),
  modo                TEXT    NOT NULL,
  acoplamiento_id     INTEGER REFERENCES acoplamientos (id) ON DELETE SET NULL,
  error               TEXT,
  publicado_en        TEXT,
  telegram_message_id INTEGER,
  ultimo_intento      TEXT,
  creado_en           TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f','now','localtime'))
);

CREATE INDEX IF NOT EXISTS idx_cola_estado ON cola_publicaciones (estado);
CREATE INDEX IF NOT EXISTS idx_cola_prog   ON cola_publicaciones (programado_para);

CREATE TABLE IF NOT EXISTS comentarios (
  id                  INTEGER PRIMARY KEY AUTOINCREMENT,
  publicacion_id      INTEGER NOT NULL REFERENCES publicaciones (id) ON DELETE CASCADE,
  telegram_message_id INTEGER NOT NULL UNIQUE,
  autor               TEXT,
  texto               TEXT,
  fecha               TEXT    NOT NULL,
  creado_en           TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f','now','localtime'))
);

CREATE INDEX IF NOT EXISTS idx_comentario_pub   ON comentarios (publicacion_id);
CREATE INDEX IF NOT EXISTS idx_comentario_fecha ON comentarios (fecha);

CREATE TABLE IF NOT EXISTS config_app (
  id                      INTEGER NOT NULL PRIMARY KEY,
  bot_token_cifrado       BLOB,
  canal_principal_cifrado BLOB,
  canal_prueba_cifrado    BLOB,
  tmdb_token_cifrado      BLOB,
  limite_caption_cifrado  BLOB,
  intervalo_cola_cifrado  BLOB,
  colgada_min_cifrado     BLOB,
  creado_en               TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f','now','localtime')),
  actualizado_en          TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f','now','localtime'))
);

CREATE TABLE IF NOT EXISTS canales (
  id        INTEGER PRIMARY KEY AUTOINCREMENT,
  nombre    TEXT    NOT NULL,
  chat_id   TEXT    NOT NULL,
  bot_id    INTEGER REFERENCES bots (id) ON DELETE SET NULL,
  tipo      TEXT    NOT NULL DEFAULT 'real' CHECK (tipo IN ('real','test')),
  activo    INTEGER NOT NULL DEFAULT 1,
  creado_en TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f','now','localtime')),
  chat_tipo TEXT,
  UNIQUE (chat_id, tipo)
);

CREATE TABLE IF NOT EXISTS acoplamiento_canales (
  acoplamiento_id INTEGER NOT NULL REFERENCES acoplamientos (id) ON DELETE CASCADE,
  canal_id        INTEGER NOT NULL REFERENCES canales (id) ON DELETE CASCADE,
  PRIMARY KEY (acoplamiento_id, canal_id)
);

CREATE TABLE IF NOT EXISTS caption_templates (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  name       TEXT    NOT NULL UNIQUE,
  template   TEXT    NOT NULL,
  is_default INTEGER NOT NULL DEFAULT 0,
  created_at TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f','now','localtime')),
  updated_at TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f','now','localtime'))
);

CREATE TABLE IF NOT EXISTS settings (
  clave          TEXT NOT NULL PRIMARY KEY,
  valor          TEXT,
  actualizado_en TEXT
);

CREATE TABLE IF NOT EXISTS sugerencias (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  tmdb_id         INTEGER NOT NULL,
  media_type      TEXT    NOT NULL,
  titulo          TEXT    NOT NULL,
  anio            TEXT,
  poster_path     TEXT,
  origen          TEXT    NOT NULL DEFAULT 'guardado',
  cargado_en      TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f','now','localtime')),
  titulo_original TEXT,
  UNIQUE (tmdb_id, media_type)
);

CREATE TABLE IF NOT EXISTS respaldos (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  tipo            TEXT    NOT NULL,
  estado          TEXT    NOT NULL DEFAULT 'en_progreso',
  motivo          TEXT    NOT NULL DEFAULT 'manual',
  archivo         TEXT    NOT NULL,
  ruta            TEXT    NOT NULL,
  tamano_bytes    INTEGER,
  checksum_sha256 TEXT,
  motor           TEXT    NOT NULL DEFAULT 'mariadb',
  version         TEXT,
  mensaje         TEXT,
  iniciado_en     TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f','now','localtime')),
  terminado_en    TEXT,
  duracion_ms     INTEGER,
  verificado_en   TEXT,
  token_ejecucion TEXT    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_resp_estado   ON respaldos (estado);
CREATE INDEX IF NOT EXISTS idx_resp_tipo     ON respaldos (tipo);
CREATE INDEX IF NOT EXISTS idx_resp_iniciado ON respaldos (iniciado_en);

CREATE TABLE IF NOT EXISTS config_respaldo (
  id                      INTEGER NOT NULL PRIMARY KEY,
  auto_activo_cifrado     BLOB,
  modo_cifrado            BLOB,
  intervalo_horas_cifrado BLOB,
  hora_diaria_cifrado     BLOB,
  dir_auto_cifrado        BLOB,
  dir_manual_cifrado      BLOB,
  bin_dir_cifrado         BLOB,
  retencion_cifrado       BLOB,
  creado_en               TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f','now','localtime')),
  actualizado_en          TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f','now','localtime'))
);
