package api.crow.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "comentarios")
@Getter
@Setter
public class Comentario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "publicacion_id", nullable = false)
    private Long publicacionId;

    @Column(name = "telegram_message_id", nullable = false, unique = true)
    private Long telegramMessageId;

    @Column(name = "autor", length = 255)
    private String autor;

    @Column(name = "texto", columnDefinition = "TEXT")
    private String texto;

    @Column(name = "fecha", nullable = false)
    private LocalDateTime fecha;

    @Column(name = "creado_en", updatable = false)
    private LocalDateTime creadoEn;

    @PrePersist
    void prePersist() {
        if (creadoEn == null) creadoEn = LocalDateTime.now();
    }
}
