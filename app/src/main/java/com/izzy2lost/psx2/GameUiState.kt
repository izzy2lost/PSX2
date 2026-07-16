package com.izzy2lost.psx2

/**
 * Representa el estado de interfaz de un juego individual dentro de PSX2.
 * Esta clase es completamente independiente del núcleo nativo de emulación,
 * garantizando que las lecturas y actualizaciones visuales se ejecuten de manera
 * asíncrona y aislada del hilo de renderizado principal del emulador PCSX2_ARM64.
 *
 * De este modo, se mitiga cualquier riesgo de corrupción en las tarjetas de memoria (.ps2)
 * o en los estados de guardado rápido (.p2s) al desligar el ciclo de vida de la UI.
 *
 * @property title El título legible del juego (por ejemplo, resuelto desde la base de datos local).
 * @property uriString La URI del archivo de juego (ISO/CHD/CSO) utilizada para la carga.
 * @property serial El identificador o número de serie de la región (por ejemplo, SLUS-20312).
 * @property coverPath La ruta o URI local/remota de la carátula o artwork asociada.
 * @property isSelected Indica si el juego está enfocado o seleccionado actualmente en el carrusel inmersivo.
 */
data class GameUiState(
    val title: String,
    val uriString: String,
    val serial: String,
    val coverPath: String?,
    val isSelected: Boolean = false
)
