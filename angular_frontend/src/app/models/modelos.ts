export type TipoContenido = 'movie' | 'tv';

export interface Titulo {
  id: number;
  tmdb_id: number;
  tipo: TipoContenido;
  titulo_original: string;
  titulo_latino: string | null;
  anio: number | null;
  generos: string | null;
  rating: number | null;
  vote_count: number | null;
  sinopsis: string | null;
  poster_path: string | null;
  poster_local_path: string | null;
  ultima_actualizacion: string | null;
}

export interface LinkVideo {
  id?: number;
  texto_boton: string;
  url: string;
  terabox_filename: string | null;
  orden: number;
}

export interface DatosLigeros {
  release_dates?: Record<string, unknown>;
  watch_providers?: Record<string, unknown>;
}

export interface TituloDetalle extends Titulo {
  datos: DatosLigeros;
  links: LinkVideo[];
}

/** Resultado de búsqueda TMDB (GET /api/tmdb/search) */
export interface TmdbHit {
  id: number;
  media_type: 'movie' | 'tv';
  titulo: string;
  anio: string;
  poster_path: string | null;
  overview: string;
}

export interface SugerenciaHit {
  encontrado: boolean;
  tmdb_id?: number;
  media_type?: 'movie' | 'tv';
  titulo?: string;
  anio?: string;
  poster_path?: string | null;
  distancia?: number;
}

export interface Publicacion {
    id: number;
    titulo_id: number;
    titulo: string;
    caption: string | null;
    estado: 'borrador' | 'publicado';
    modo?: 'real' | 'test' | null;
    telegram_message_id: number | null;
    telegram_chat_id?: string | null;
    telegram_grupo_message_id?: number | null;
    telegram_grupo_chat_id?: string | null;
    chat_dedicado?: boolean;
    comentarios_count?: number;
    fecha_publicacion: string | null;
    fecha_editado?: string | null;
    fecha_republicado?: string | null;
  }

/** Fila ligera del catálogo (GET /api/publicaciones/catalogo): sin datos pesados. */
export interface CatalogoFila {
  id: number;
  publicacion_id: number | null;
  titulo: string;
  tipo: 'movie' | 'tv';
  anio: number | null;
  generos: string | null;
  rating: number | null;
  poster_path: string | null;
  estado: 'borrador' | 'publicado' | 'sin';
  fecha: string | null;
  comentarios_count: number;
}

export interface Comentario {
  id: number;
  autor: string;
  texto: string;
  fecha: string;
}

export interface CatalogoRespuesta {
  total: number;
  borradores: number;
  publicados: number;
  sin: number;
  items: CatalogoFila[];
}

export function nombreMostrable(t: { titulo_latino: string | null; titulo_original: string }): string {
  return t.titulo_latino && t.titulo_latino.trim().length ? t.titulo_latino.trim() : t.titulo_original;
}

export function urlPoster(posterPath: string | null, tam = 'w780'): string | null {
  return posterPath ? `https://image.tmdb.org/t/p/${tam}${posterPath}` : null;
}

export function etiquetaTipo(tipo: TipoContenido): string {
  return tipo === 'movie' ? 'PELÍCULA' : 'SERIE';
}