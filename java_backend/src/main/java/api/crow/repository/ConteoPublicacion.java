package api.crow.repository;

/**
 * Proyección de conteo agrupado por publicación.
 *
 * <p>No usar {@code Map<Long, Long>} directamente en las consultas: Spring Data
 * devuelve un mapa respaldado por tuplas cuyas claves son los alias de la
 * consulta y {@code containsKey(Long)} lanza {@link ClassCastException}.</p>
 */
public interface ConteoPublicacion {

    Long getPubId();

    Long getCnt();
}
