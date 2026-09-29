/*
 * Copyright (C) 2025-2026 Flow | A-EDev
 *
 * This file is part of Flow (https://github.com/A-EDev/Flow).
 */

package io.github.aedev.flow.ui.components.shared

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Shape

/**
 * The shape behind a shuffle or radio action, distinct from both artists and collections.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun flowActionShape(): Shape = MaterialShapes.Cookie12Sided.toShape()
