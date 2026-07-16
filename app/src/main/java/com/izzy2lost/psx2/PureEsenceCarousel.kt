// SPDX-FileCopyrightText: 2025 Android Port Contributors
// SPDX-License-Identifier: GPL-3.0+

package com.izzy2lost.psx2

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/**
 * Componente principal de carrusel inmersivo de PSX2 que implementa la estética de "Esencia Pura".
 *
 * Sigue la filosofía de diseño "Capa Cero", donde se remueven todas las tarjetas de fondo,
 * bordes y contenedores artificiales. La carátula del juego es la única estructura visual protagonista.
 *
 * La navegación es fluida, horizontal y con scroll táctil de alto rendimiento (60fps/120Hz).
 * Utiliza Coil para la carga de imágenes asíncronas y optimizadas fuera de hilos críticos.
 * No genera bloqueos en la máquina virtual PCSX2 ni interfiere con las operaciones críticas
 * de guardado (.ps2 o .p2s) dado que desacopla la obtención de datos de la lógica de interfaz de usuario.
 *
 * @param games Lista de estados de juegos actuales.
 * @param onGameSelected Callback invocado al seleccionar de forma interactiva un juego.
 * @param onGameLongClick Callback invocado al realizar pulsación larga para acceder a configuraciones por juego.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PureEsenceCarousel(
    games: List<GameUiState>,
    onGameSelected: (GameUiState) -> Unit,
    onGameLongClick: (GameUiState) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val snapFlingBehavior = rememberSnapFlingBehavior(lazyListState = listState)

    // El estado del índice centrado o seleccionado para las transiciones suaves
    var selectedIndex by remember { mutableIntStateOf(0) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            // LazyRow horizontal con snapping inmersivo
            LazyRow(
                state = listState,
                flingBehavior = snapFlingBehavior,
                contentPadding = PaddingValues(horizontal = 80.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                itemsIndexed(games) { index, game ->
                    val isSelected = index == selectedIndex

                    // Animaciones suaves de escala e iluminación de Esencia Pura
                    val scale by animateFloatAsState(
                        targetValue = if (isSelected) 1.15f else 0.85f,
                        animationSpec = tween(durationMillis = 350),
                        label = "ScaleAnimation"
                    )

                    val luminance by animateFloatAsState(
                        targetValue = if (isSelected) 1.0f else 0.5f,
                        animationSpec = tween(durationMillis = 350),
                        label = "LuminanceAnimation"
                    )

                    // Aplicamos matriz de color para cambiar la saturación y brillo según selección (luminancia)
                    val colorMatrix = remember(luminance) {
                        ColorMatrix().apply {
                            setToScale(luminance, luminance, luminance, 1.0f)
                        }
                    }

                    Box(
                        modifier = Modifier
                            .width(160.dp)
                            .fillMaxHeight()
                            .scale(scale)
                            .combinedClickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null, // Cero cajas ni ripples para pureza absoluta
                                onClick = {
                                    selectedIndex = index
                                    onGameSelected(game)
                                },
                                onLongClick = {
                                    onGameLongClick(game)
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        // Carátula flotante sin bordes artificiales, solo esquinas redondeadas sumamente suaves
                        AsyncImage(
                            model = game.coverPath,
                            contentDescription = game.title,
                            contentScale = ContentScale.Crop,
                            colorFilter = ColorFilter.colorMatrix(colorMatrix),
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(24.dp))
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Textos y títulos minimalistas flotando de forma etérea sobre la pantalla
            if (games.isNotEmpty() && selectedIndex in games.indices) {
                val currentSelected = games[selectedIndex]

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                ) {
                    Text(
                        text = currentSelected.title,
                        style = TextStyle(
                            color = Color.White,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.SansSerif,
                            textAlign = TextAlign.Center,
                            shadow = Shadow(
                                color = Color.Black.copy(alpha = 0.6f),
                                offset = Offset(0f, 4f),
                                blurRadius = 8f
                            )
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = currentSelected.serial,
                        style = TextStyle(
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Light,
                            shadow = Shadow(
                                color = Color.Black.copy(alpha = 0.5f),
                                offset = Offset(0f, 2f),
                                blurRadius = 4f
                            )
                        )
                    )
                }
            }
        }
    }
}
