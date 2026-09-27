# sepa-relay

`datos.produccion.gob.ar` le responde **403 a la IP del VPS** (está en Chile) y no a una conexión residencial argentina. Este relay corre en un equipo de acá, baja el dataset SEPA y lo deja en una carpeta del VPS que el backend vigila. No hay que cargar archivos ni fechas a mano.

```
PC en Argentina                         VPS (Easypanel)
─────────────────                       ─────────────────────────────────────
tarea cada hora                         /opt/ofertar/sepa-incoming  (bind mount)
  │ consulta la API de SEPA                   │
  │ ¿hay un dataset nuevo? ── no → fin        │  backend: SEPA_RESOURCE_DIR
  │ baja el zip (~300 MB)                     │  cron cada hora:
  │ valida y lee la fecha de adentro          │   ¿el zip más nuevo es más nuevo
  └─ scp → sepa_AAAA-MM-DD.zip.part ──────────▶   que lo cargado? → importa
     ssh → mv a .zip + deja los 2 últimos      │   si no → nada
```

- **La fecha sale de adentro del zip** (la carpeta `AAAA-MM-DD/` con la que lo arma SEPA), no del nombre: `sepa_martes.zip` no la dice.
- **Nunca se lee un archivo a medio subir:** se sube como `.part` y se renombra al final; el backend ignora todo lo que no termina en `.zip`.
- **La pasada horaria es barata:** sin novedades, es una consulta chica a la API. Los 300 MB se bajan una vez por dataset y se reanudan si se cortan.
- **El backend no reimporta:** el cron se saltea si el dataset de la carpeta no es más nuevo que el cargado (`sepa_producto.fecha_dataset`).

## Puesta en marcha

### 1. En el VPS (una vez, por SSH con tu usuario de siempre)

Un usuario propio, sin `sudo`, que sólo sirve para dejar el zip:

```bash
sudo adduser --disabled-password --gecos "" sepa
sudo mkdir -p /opt/ofertar/sepa-incoming
sudo chown sepa:sepa /opt/ofertar/sepa-incoming
sudo chmod 755 /opt/ofertar/sepa-incoming
```

### 2. En Easypanel, en el servicio del backend

- **Mounts → Bind mount:** host `/opt/ofertar/sepa-incoming` → contenedor `/data/sepa-incoming`.
- **Environment:**
  - `SEPA_RESOURCE_DIR=/data/sepa-incoming`
  - `SEPA_SYNC_CRON=0 20 * * * *` (cada hora, a los :20)
  - **Borrá** `SEPA_RESOURCE_FILE` y `SEPA_RESOURCE_FECHA` si estaban: el archivo fijo le gana a la carpeta.
- Deploy.

### 3. En la PC: una clave SSH sólo para esto

En PowerShell. Cuando pida passphrase, Enter dos veces (la tarea corre sin nadie delante y no podría escribirla):

```powershell
ssh-keygen -t ed25519 -f "$env:USERPROFILE\.ssh\ofertar_sepa" -C "ofertar-sepa-relay"
Get-Content "$env:USERPROFILE\.ssh\ofertar_sepa.pub"
```

Copiá la línea que imprime y, en el VPS, agregala con `restrict` adelante (sin reenvío de puertos, sin terminal):

```bash
sudo mkdir -p /home/sepa/.ssh
echo 'restrict ssh-ed25519 AAAA...la-línea-copiada... ofertar-sepa-relay' | sudo tee -a /home/sepa/.ssh/authorized_keys
sudo chown -R sepa:sepa /home/sepa/.ssh
sudo chmod 700 /home/sepa/.ssh && sudo chmod 600 /home/sepa/.ssh/authorized_keys
```

Primera conexión a mano, para comparar la huella del servidor antes de aceptarla. En el VPS, `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub` muestra la huella real; en la PC:

```powershell
ssh -i "$env:USERPROFILE\.ssh\ofertar_sepa" sepa@TU-VPS "ls -la /opt/ofertar/sepa-incoming"
```

### 4. Configuración

Copiá `sepa-relay.config.example.psd1` como `sepa-relay.config.psd1` (está en `.gitignore`) y completá `VpsHost`, y lo demás si lo cambiaste.

### 5. Probar con el zip que ya tenés

Sube un zip ya bajado, sin depender de que el sitio esté arriba:

```powershell
powershell -ExecutionPolicy Bypass -File .\sepa-relay.ps1 -ZipLocal "$env:USERPROFILE\Downloads\sepa_martes.zip"
```

En el VPS tiene que aparecer `/opt/ofertar/sepa-incoming/sepa_2026-09-15.zip`. El backend lo toma en la próxima pasada del cron (o al instante con el sync manual, `POST /sepa/sync`).

### 6. Dejarlo programado

```powershell
powershell -ExecutionPolicy Bypass -File .\instalar-tarea.ps1
```

Corre cada hora. Si la PC estaba apagada, corre apenas vuelve. Por defecto, sólo con la sesión de Windows iniciada. Para que corra también con la sesión cerrada, abrí la tarea "OfertAR SEPA relay" en el Programador de tareas y marcá "Ejecutar tanto si el usuario inició sesión como si no".

Para borrarla: `.\instalar-tarea.ps1 -Desinstalar`.

## Cómo saber si anda

- **En la PC:** `%LOCALAPPDATA%\ofertar-sepa\relay.log`, una línea por paso. `estado.json` guarda el último dataset subido.
- **Códigos de salida** (columna "Resultado de la última ejecución" en el Programador de tareas):
  - `0`: subido, o nada nuevo.
  - `1`: configuración mal. No se arregla sola; revisá el log.
  - `2`: transitorio (sitio caído, red, SSH). Se reintenta en la próxima hora.
- **En el backend:** el log dice `SEPA: recurso por override (fecha …)` al importar, y `el dataset disponible (…) ya está cargado` cuando no hay nada nuevo. `GET /sepa/sync/estado` muestra la última sincronización.

## Pruebas

```powershell
$env:SEPA_TEST_ZIP = "$env:USERPROFILE\Downloads\sepa_martes.zip"   # opcional
powershell -ExecutionPolicy Bypass -File .\probar-sepa-relay.ps1
```

No usan red ni el VPS. Verifican:
- que la fecha y la elección del recurso siguen las mismas reglas que el backend (`SepaService`);
- que la configuración rechaza lo que iría mal dentro de un comando de shell remoto;
- el comando remoto, corrido de verdad en bash (renombrado y limpieza);
- el script entero con sus códigos de salida.

Del lado del backend: `SepaServiceCarpetaVigiladaTest` y `SepaSnapshotServiceYaCargadoTest`.
