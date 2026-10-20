package api.crow.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "links_video")
@Getter
@Setter
public class LinkVideo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Enlace de un título; null cuando el enlace pertenece a un post personalizado. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "titulo_id")
    private Titulo titulo;

    /** Enlace propio de un post personalizado; null cuando pertenece a un título. */
    @Column(name = "publicacion_id")
    private Long publicacionId;

    @Column(name = "texto_boton", nullable = false, length = 120)
    @NotBlank(message = "El texto del botón es obligatorio.")
    @Size(max = 120, message = "El texto del botón no puede superar los 120 caracteres.")
    private String textoBoton;

    @Column(name = "url", nullable = false, columnDefinition = "TEXT")
    @NotBlank(message = "La URL del enlace es obligatoria.")
    private String url;

    @Column(name = "terabox_filename", length = 255)
    @Size(max = 255, message = "El nombre del archivo no puede superar los 255 caracteres.")
    private String teraboxFilename;

    @Column(name = "orden")
    @PositiveOrZero(message = "El orden no puede ser negativo.")
    private Integer orden;

    @PrePersist
    void prePersist() {
        if (orden == null) orden = 0;
    }
}