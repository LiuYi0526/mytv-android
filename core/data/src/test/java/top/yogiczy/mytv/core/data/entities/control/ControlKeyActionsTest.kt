package top.yogiczy.mytv.core.data.entities.control

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlKeyActionsTest {
    @Test
    fun missingAndUnknownMappingsFallBackToDefaults() {
        assertEquals(33, ControlKey.entries.size)
        ControlKey.entries.forEach { ControlKeyActions.actionFor(it, emptyMap()) }
        assertEquals(ControlAction.CHANNELS, ControlKeyActions.actionFor(ControlKey.ON_SELECT, mapOf("ON_SELECT" to "FUTURE_ACTION")))
        assertEquals(ControlAction.EPG_PROGRAMME_LIST, ControlKeyActions.actionFor(ControlKey.ON_LONG_LEFT, emptyMap()))
        assertEquals(ControlAction.VIDEO_PLAYER_CONTROLLER, ControlKeyActions.actionFor(ControlKey.ON_LONG_DOWN, emptyMap()))
    }

    @Test
    fun explicitNoneAndCustomActionsOverrideDefaults() {
        assertEquals(ControlAction.NONE, ControlKeyActions.actionFor(ControlKey.ON_SELECT, mapOf("ON_SELECT" to "NONE")))
        assertEquals(ControlAction.QUICK_OP, ControlKeyActions.actionFor(ControlKey.ON_SELECT, mapOf("ON_SELECT" to "QUICK_OP")))
    }

    @Test
    fun channelFlipOnlyChangesUnmappedDirections() {
        assertEquals(ControlAction.NEXT_CHANNEL, ControlKeyActions.actionFor(ControlKey.ON_UP, emptyMap(), true))
        assertEquals(ControlAction.PREVIOUS_CHANNEL, ControlKeyActions.actionFor(ControlKey.ON_DOWN, emptyMap(), true))
        assertEquals(ControlAction.PREVIOUS_CHANNEL, ControlKeyActions.actionFor(ControlKey.ON_UP, mapOf("ON_UP" to "PREVIOUS_CHANNEL"), true))
    }

    @Test
    fun sanitizesUnknownAndUnusableNumericMappings() {
        assertEquals(
            mapOf("ON_SELECT" to "NONE", "ON_NUMBER" to "CHANNEL_NO_SELECT"),
            ControlKeyActions.sanitize(mapOf(
                "ON_SELECT" to "NONE", "ON_NUMBER" to "CHANNEL_NO_SELECT", "ON_UP" to "CHANNEL_NO_SELECT",
                "FUTURE_KEY" to "CHANNELS", "ON_DOWN" to "FUTURE_ACTION",
            )),
        )
    }

    @Test
    fun touchGridResolvesAllElevenRegionsAndLongPressPartners() {
        val points = listOf(
            Triple(.1f, .1f, ControlKey.ON_TOP_START), Triple(.5f, .1f, ControlKey.ON_TOP_CENTER),
            Triple(.9f, .1f, ControlKey.ON_TOP_END), Triple(.1f, .9f, ControlKey.ON_BOTTOM_START),
            Triple(.5f, .9f, ControlKey.ON_BOTTOM_CENTER), Triple(.9f, .9f, ControlKey.ON_BOTTOM_END),
            Triple(.1f, .5f, ControlKey.ON_CENTER_START), Triple(.3f, .5f, ControlKey.ON_CENTER_CENTER_START),
            Triple(.5f, .5f, ControlKey.ON_CENTER_CENTER_CENTER), Triple(.7f, .5f, ControlKey.ON_CENTER_CENTER_END),
            Triple(.9f, .5f, ControlKey.ON_CENTER_END),
        )
        points.forEach { (x, y, key) ->
            assertEquals(key, ControlKeyActions.touchKey(x, y, false))
            assertEquals(ControlKey.valueOf(key.name.replace("ON_", "ON_LONG_")), ControlKeyActions.touchKey(x, y, true))
        }
        assertEquals(ControlKey.ON_TOP_START, ControlKeyActions.touchKey(0f, 0f, false))
        assertEquals(ControlKey.ON_BOTTOM_END, ControlKeyActions.touchKey(1f, 1f, false))
    }

    @Test
    fun overridesSurviveJsonRoundTripAndRemovingOverrideRestoresDefault() {
        val overrides = mapOf("ON_SELECT" to "QUICK_OP", "ON_LONG_UP" to "NONE")
        val restored = Json.decodeFromString<Map<String, String>>(Json.encodeToString(overrides))
        assertEquals(overrides, restored)
        assertEquals(ControlAction.CHANNELS, ControlKeyActions.actionFor(ControlKey.ON_SELECT, restored - "ON_SELECT"))
    }

    @Test
    fun shortPressFiresOnlyOnce() {
        val state = ControlKeyPressTracker()
        assertFalse(state.down(23, 0))
        assertTrue(state.up(23))
        assertFalse(state.up(23))
    }

    @Test
    fun longPressFiresOnceAndSuppressesShortPress() {
        val state = ControlKeyPressTracker()
        assertFalse(state.down(23, 0))
        assertTrue(state.down(23, 1))
        assertFalse(state.down(23, 2))
        assertFalse(state.up(23))
        assertFalse(state.up(23))
    }

    @Test
    fun cancellationAndFocusLossDoNotFireActions() {
        val state = ControlKeyPressTracker()
        state.down(19, 0)
        assertFalse(state.up(19, cancelled = true))
        state.down(19, 0)
        state.clear()
        assertFalse(state.up(19))
        assertFalse(state.down(19, 1))
    }
}
