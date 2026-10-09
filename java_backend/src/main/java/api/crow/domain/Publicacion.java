package api.crow.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "publicaciones")
@Getter
@Setter
public class Publicacion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "titulo_id", nullable = false)
    private Titulo titulo;

    @Column(name = "caption", columnDefinition = "TEXT")
    private String caption;

    @Column(name = "estado", length = 20)
    private String estado;

    @Column(name = "modo", length = 8)
    private String modo;

    @Column(name = "telegram_message_id")
    private Long telegramMessageId;

    @Column(name = "telegram_chat_id", length = 64)
    private String telegramChatId;

    @Column(name = "telegram_grupo_message_id")
    private Long telegramGrupoMessageId;

    @Column(name = "chat_dedicado")
    private Boolean chatDedicado = true;

    @Column(name = "fecha_publicacion")
    private LocalDateTime fechaPublicacion;

    @Column(name = "fecha_editado")
    private LocalDateTime fechaEditado;

    @Column(name = "fecha_republicado")
    private LocalDateTime fechaRepublicado;

    @Column(name = "creado_en", updatable = false)
    private LocalDateTime creadoEn;

    @PrePersist
    void prePersist() {
        if (creadoEn == null) creadoEn = LocalDateTime.now();
        if (estado == null) estado = "borrador";
    }
}