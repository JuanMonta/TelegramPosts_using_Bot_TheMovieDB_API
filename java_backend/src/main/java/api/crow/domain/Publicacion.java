package api.crow.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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

    /** Título de TMDB. Es null en los posts personalizados (avisos/noticias). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "titulo_id")
    private Titulo titulo;

    /** 'titulo' (post de una película/serie) | 'personalizado' (sin TMDB). */
    @Column(name = "tipo", length = 16)
    @NotBlank(message = "El tipo de publicación es obligatorio.")
    @Size(max = 16, message = "El tipo no puede superar los 16 caracteres.")
    private String tipo = "titulo";

    @Column(name = "caption", columnDefinition = "TEXT")
    private String caption;

    @Column(name = "estado", length = 20)
    @Size(max = 20, message = "El estado no puede superar los 20 caracteres.")
    private String estado;

    @Column(name = "modo", length = 8)
    @Size(max = 8, message = "El modo no puede superar los 8 caracteres.")
    private String modo;

    @Column(name = "acoplamiento_id")
    private Long acoplamientoId;

    @Column(name = "telegram_message_id")
    private Long telegramMessageId;

    @Column(name = "telegram_chat_id", length = 64)
    @Size(max = 64, message = "El chat de Telegram no puede superar los 64 caracteres.")
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
        if (tipo == null || tipo.isBlank()) tipo = "titulo";
    }
}