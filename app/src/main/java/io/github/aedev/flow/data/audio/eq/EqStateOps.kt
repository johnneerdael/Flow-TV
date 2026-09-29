package io.github.aedev.flow.data.audio.eq

/**
 * Every change the equalizer supports, as pure functions of [EqState]. The repository applies them
 * and persists the result; the tests exercise them without Android.
 */
fun EqState.userPreset(id: String?): EqPreset? = id?.let { wanted -> userPresets.firstOrNull { it.id == wanted } }

fun EqState.presetCurve(id: String?): EqCurve? = BuiltInEqPresets.byId(id)?.curve ?: userPreset(id)?.curve

private fun EqState.presetMode(id: String?): EqMode? =
    when {
        BuiltInEqPresets.isBuiltIn(id) -> EqMode.PARAMETRIC
        else -> userPreset(id)?.mode
    }

/**
 * Preset [id] as the given [mode] plays it: a graphic preset's ten bands load as parametric bands as
 * they are, and a parametric preset is fitted onto the ten graphic bands.
 */
fun EqState.presetCurveFor(
    mode: EqMode,
    id: String?,
): EqCurve? {
    val curve = presetCurve(id) ?: return null
    return when {
        mode == EqMode.PARAMETRIC -> curve.sanitized()
        presetMode(id) == EqMode.GRAPHIC -> sanitizedFor(EqMode.GRAPHIC, curve)
        else -> GraphicEq.fit(curve)
    }
}

/** The equalizer is on and something in it alters the signal. */
val EqState.changesSound: Boolean
    get() =
        enabled &&
            (
                bassBoost > 0.0 ||
                    active.curve.bands.any { !EqFilterMath.isBypassed(it, EqFilterMath.REFERENCE_SAMPLE_RATE) } ||
                    (!autoPreamp && active.curve.preamp != 0.0)
            )

private fun EqState.withActive(working: EqWorkingCopy): EqState =
    if (mode == EqMode.PARAMETRIC) copy(parametric = working) else copy(graphic = working)

/** Plays preset [id] in the current mode; the mode itself never changes here. */
fun EqState.selectPreset(id: String): EqState {
    val curve = presetCurveFor(mode, id) ?: return this
    return withActive(EqWorkingCopy(id, curve))
}

fun normalizedPresetName(raw: String): String = raw.trim().take(EqLimits.MAX_NAME_LENGTH)

/**
 * What the processors should run. [preview] replaces the active curve while a point is dragged, so
 * the sound follows the finger without the saved state or the UI changing every frame.
 */
fun EqState.processingSpec(
    bypass: Boolean,
    preview: EqCurve? = null,
): EqProcessingSpec {
    if (!enabled || bypass) return EqProcessingSpec.OFF
    val curve = preview ?: active.curve
    val bands =
        buildList {
            addAll(curve.bands.filter { it.enabled })
            if (bassBoost > 0.0) {
                add(EqBand(EqLimits.BASS_BOOST_FREQUENCY, bassBoost, EqLimits.DEFAULT_SHELF_Q, EqFilterType.LOW_SHELF))
            }
        }
    val preamp = if (autoPreamp) EqFilterMath.autoPreampDb(bands) else curve.preamp
    return EqProcessingSpec(enabled = true, preampDb = preamp, bands = bands)
}

/** Clamps every value and repairs anything a hand-edited backup or an old version could hold. */
fun EqState.sanitized(): EqState =
    copy(
        parametric = parametric.copy(curve = parametric.curve.sanitized()),
        graphic = graphic.copy(curve = sanitizedFor(EqMode.GRAPHIC, graphic.curve)),
        bassBoost = bassBoost.takeIf { it.isFinite() }?.coerceIn(0.0, EqLimits.MAX_BASS_BOOST) ?: 0.0,
        userPresets =
            userPresets
                .filter { it.id.isNotBlank() && it.name.isNotBlank() }
                .distinctBy { it.id }
                .map { it.copy(name = normalizedPresetName(it.name), curve = sanitizedFor(it.mode, it.curve)) },
    )

private fun sanitizedFor(
    mode: EqMode,
    curve: EqCurve,
): EqCurve =
    if (mode == EqMode.GRAPHIC) {
        GraphicEq.curveOf(GraphicEq.gainsOf(curve), curve.sanitized().preamp)
    } else {
        curve.sanitized()
    }
