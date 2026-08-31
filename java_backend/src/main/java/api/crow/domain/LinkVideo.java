package api.crow.domain;

import jakarta.persistence.*;
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

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "titulo_id", nullable = false)
    private Titulo titulo;

    @Column(name = "texto_boton", nullable = false, length = 120)
    private String textoBoton;

    @Column(name = "url", nullable = false, columnDefinition = "TEXT")
    private String url;

    @Column(name = "terabox_filename", length = 255)
    private String teraboxFilename;

    @Column(name = "orden")
    private Integer orden;

    @PrePersist
    void prePersist() {
        if (orden == null) orden = 0;
    }
}