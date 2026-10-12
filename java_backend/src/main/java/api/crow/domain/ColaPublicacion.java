package api.crow.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Item de la cola de publicaciones programadas. Cuando llega su hora, el bot
 * publica el borrador vinculado en el canal del modo en que se programó.
 */
@Entity
@Table(name = "cola_publicaciones")
@Getter
@Setter
public class ColaPublicacion {

    public static final String ESTADO_PENDIENTE = "pendiente";
    public static final String ESTADO_PUBLICANDO = "publicando";
    public static final String ESTADO_PUBLICADO = "publicado";
    public static final String ESTADO_FALLO = "fallo";
    public static final String ESTADO_CANCELADO = "cancelado";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "publicacion_id", nullable = false)
    private Publicacion publicacion;

    @Column(name = "programado_para")
    private LocalDateTime programadoPara;

    @Column(name = "estado", length = 20)
    private String estado;

    @Column(name = "modo", length = 8)
    private String modo;

    @Column(name = "acoplamiento_id")
    private Long acoplamientoId;

    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

    @Column(name = "publicado_en")
    private LocalDateTime publicadoEn;

    @Column(name = "telegram_message_id")
    private Long telegramMessageId;

    @Column(name = "ultimo_intento")
    private LocalDateTime ultimoIntento;

    @Column(name = "creado_en", updatable = false)
    private LocalDateTime creadoEn;

    @PrePersist
    void prePersist() {
        if (creadoEn == null) creadoEn = LocalDateTime.now();
        if (estado == null) estado = ESTADO_PENDIENTE;
    }
}