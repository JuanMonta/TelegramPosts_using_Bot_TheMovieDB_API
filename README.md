<p align="center">
  <img src="CanalVideos.png" width="88" alt="Icono de CanalVideos">
</p>

<h1 align="center">CanalVideos</h1>

<p align="center">
  <strong>Organiza, prepara y publica contenido en tu canal de Telegram.</strong>
</p>

<p align="center">
  Windows 10/11 · Todo local · Sin instalar nada más
</p>

![Vista principal de CanalVideos](https://github.com/JuanMonta/TelegramPosts_using_Bot_TheMovieDB_API/blob/master/image-resources/01-home.png?raw=true)

*Vista principal: catálogo local, búsqueda y vista previa del post. Las capturas usan contenido de demostración.*

## Qué es

CanalVideos es una herramienta local para organizar y publicar contenido en canales y grupos de Telegram.

Reúne en una sola pantalla la ficha, la imagen, el texto del post, los botones, los enlaces y la vista previa del mensaje. Cuando todo está listo, publica al instante o deja la publicación programada.

La integración con TMDB se incluye como ejemplo de fuente externa de datos: puedes importar fichas y pósters de películas y series, o crear publicaciones personalizadas con tu propio contenido.

No necesitas una cuenta en la nube ni saber programar. La aplicación y tus datos viven en tu PC.

## Qué puedes hacer

- Crear publicaciones para canales, grupos o chats donde el bot tenga permiso.
- Importar fichas desde TMDB como ejemplo de integración con una API externa, o crear contenido propio.
- Guardar un catálogo local de fichas y publicaciones.
- Añadir varios botones con texto, URL y una nota interna opcional.
- Redactar el texto con plantillas y guardado automático.
- Ver exactamente cómo quedará el mensaje en Telegram.
- Publicar al instante o programar la publicación.
- Editar, actualizar o volver a publicar un mensaje ya enviado.
- Trabajar en modo **PRUEBA** sin que nada llegue a tu destino público.
- Organizar varios destinos con los **conjuntos**.

## Antes de empezar

- Windows 10 u 11 de 64 bits.
- Al menos 1,5 GB libres para la primera compilación.
- Conexión a internet para la primera puesta en marcha, la importación desde TMDB y Telegram.
- Un navegador moderno.

No necesitas instalar Java, Node ni Maven. La base de datos se crea sola; no hace falta instalar gestores de bases de datos.

## Primeros pasos

### 1. Abre la aplicación

1. Descarga o copia la carpeta completa del proyecto en una ruta fija, por ejemplo `D:\CanalVideos`.
2. Haz doble clic en **`AsistenteDeCompilacion-CanalVideos.bat`**.
3. Elige **`[1] Abrir CanalVideos`** si ya está compilada, o **`[2] Compilar y abrir`** la primera vez.
4. Espera: la primera compilación puede tardar varios minutos. La app se abrirá sola en tu navegador.

El asistente descarga Java, Node y Maven de forma portátil si hacen falta. No modifica nada en tu instalación de Windows.

> Si el asistente pide **Visual C++**, elige la opción **`[5]`**. Es un requisito de Windows y el asistente te guía para instalarlo.

### 2. Conecta TMDB (opcional)

TMDB es la integración de ejemplo para importar fichas y pósters. Si vas a crear publicaciones personalizadas, puedes saltarte este paso.

1. Crea una cuenta gratuita en [themoviedb.org](https://www.themoviedb.org/).
2. Entra en **Configuración → API** y copia el **API Read Access Token**.
3. En CanalVideos, abre **Configuración** (el engranaje) → **Ajustes**.
4. Pega el token en **TOKEN DE TMDB** y pulsa **GUARDAR**.

### 3. Conecta tu bot y tus destinos

1. En Telegram, habla con [@BotFather](https://t.me/BotFather), crea un bot y copia su token.
2. Añade el bot como **administrador** de tu canal o grupo.
3. En CanalVideos, abre **Configuración → Bots** y registra el bot.
4. En **Configuración → Destinos**, escribe el `@usuario` o el ID de tu destino, pulsa **DETECTAR** y asígnale el bot.

Si quieres publicar en varios canales o grupos a la vez, créalos primero en **Destinos** y luego reúnelos en **Conjuntos**.

## Busca un título

Escribe el nombre, el ID de TMDB o pega un enlace de TMDB en la barra de búsqueda. Pulsa `Enter` y elige el resultado correcto.

![Búsqueda de títulos en TMDB](https://github.com/JuanMonta/TelegramPosts_using_Bot_TheMovieDB_API/blob/master/image-resources/02-buscar.png?raw=true)

*Los resultados de TMDB aparecen a la izquierda; el título seleccionado, a la derecha.*

Al guardar un título se añade a tu catálogo local. Desde ese momento puedes abrirlo cuando quieras sin volver a buscarlo.

También puedes crear una publicación propia con **NUEVO POST**, sin importar una ficha de TMDB.

## Revisa y publica

Abre un título con **ABRIR EN EL GESTOR** o crea un post con **NUEVO POST**. Allí puedes:

1. Revisar la ficha, la imagen y los datos.
2. Añadir botones y enlaces. Cada fila tiene tres campos:
   - **Texto visible**: lo que verá el usuario, por ejemplo «Ver más» o «Abrir recurso».
   - **URL de destino**: cualquier enlace válido de Telegram o de otra web.
   - **Notas internas (opcional)**: información de apoyo para ti, como la versión, la procedencia o un recordatorio. No se publica en Telegram.
3. Editar el texto del post o aplicar una plantilla.
4. Comprobar la vista previa.
5. Pulsar **PUBLICAR** o **PROGRAMAR**.

Puedes añadir varias filas, cambiar su orden con las flechas o eliminarlas con la **X**. El campo de notas es opcional y no limita el tipo de contenido.

![Editor y vista previa de un post](https://github.com/JuanMonta/TelegramPosts_using_Bot_TheMovieDB_API/blob/master/image-resources/03-editor.png?raw=true)

*Editor a la izquierda y vista previa del mensaje a la derecha. Los botones y el campo de notas de la captura son de ejemplo.*

Si el post ya está publicado, puedes **actualizarlo**, **eliminarlo y volver a publicarlo** o **agregar un comentario** en el grupo de discusión.

## Modo REAL y PRUEBA

El selector **REAL / PRUEBA** decide dónde se publica:

- **REAL**: tu canal público. Lo ve todo el mundo.
- **PRUEBA**: un canal privado de ensayo. Nadie más lo ve.

Usa **PRUEBA** hasta que el post esté perfecto. El cambio se aplica al instante y no afecta a los borradores.

## Consejos

- Trabaja siempre sobre un **borrador** y revisa la vista previa antes de publicar.
- Usa plantillas para mantener un mismo estilo en todos los posts.
- El contador del editor te avisa si el texto supera el límite de Telegram.
- La cola de publicaciones permite programar varios posts y ver su estado.
- En **Configuración → Datos** puedes revisar la base de datos y las copias de seguridad.
- Si usas varios destinos, los **conjuntos** evitan repetir la misma selección en cada post.

## Si algo no abre

- Ejecuta **`AsistenteDeCompilacion-CanalVideos.bat`** y elige **`[4] Diagnóstico del sistema`**.
- Revisa `logs\guiado-errores.log` para ver el detalle del último problema.
- Si falta Visual C++, elige **`[5]`** en el asistente.
- Si TMDB no responde, comprueba el token en **Configuración → Ajustes** y tu conexión a internet.
- Si Telegram no publica, confirma que el bot es administrador del destino y que elegiste el canal correcto.
- Si aparece un aviso sobre la configuración cifrada, abre la app desde la misma carpeta y no borres `java_backend\clave_maestra.bin`.
- Si el puerto habitual está ocupado, el asistente elige otro libre y abre la app automáticamente.

## Privacidad

- Tus datos, pósters y borradores se guardan en tu PC.
- Los tokens se almacenan protegidos localmente.
- CanalVideos no envía tus datos a servidores propios.
- Solo TMDB y Telegram reciben la información necesaria para buscar y publicar.
- No compartas la carpeta de configuración, los logs ni la clave maestra.

## Tecnología

CanalVideos usa Angular para la interfaz y Spring Boot (Java 17) para el backend. Los scripts de Windows preparan el entorno y los runtimes portátiles automáticamente.

No necesitas conocer el stack para usarlo.

---

<p align="center">
  Hecho para uso personal · Las capturas usan datos de demostración
</p>

<p align="center">
  Este producto usa la API de TMDB, pero no está avalado ni certificado por TMDB.<br>
  Telegram es una marca de Telegram Messenger LLP. CanalVideos no está afiliado a Telegram.
</p>
