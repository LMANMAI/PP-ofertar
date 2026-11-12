# Configuración de sepa-relay.ps1. Copiá este archivo como
# sepa-relay.config.psd1 (ése está en .gitignore) y completalo.
#
# Es un archivo de datos de PowerShell: sólo valores literales, sin variables.
# En las rutas locales se pueden usar variables de entorno con %...%.
@{
    # El VPS donde corre el backend (el de Easypanel).
    VpsHost          = 'mi-vps.ejemplo.com'
    Puerto           = 22

    # Usuario del VPS dueño de la carpeta de abajo. Conviene uno propio, sin
    # sudo, que sólo sirva para esto (ver README).
    VpsUsuario       = 'sepa'

    # Clave privada SSH sin passphrase, creada sólo para esto (ver README). La
    # tarea programada corre sin nadie delante: no puede pedir una passphrase.
    ClaveSsh         = '%USERPROFILE%\.ssh\ofertar_sepa'

    # Carpeta del VPS montada en el contenedor del backend. Es la ruta del
    # HOST, no la de adentro del contenedor (ésa va en SEPA_RESOURCE_DIR).
    CarpetaRemota    = '/opt/ofertar/sepa-incoming'

    # Cuántos zips dejar en el VPS: el más nuevo más uno de respaldo. Cada uno
    # pesa ~300 MB.
    ConservarRemotos = 2

    # Dónde se baja el zip antes de subirlo, y dónde quedan relay.log y
    # estado.json.
    CarpetaLocal     = '%LOCALAPPDATA%\ofertar-sepa'
}
