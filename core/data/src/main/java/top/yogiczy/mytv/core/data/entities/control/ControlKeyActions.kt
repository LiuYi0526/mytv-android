package top.yogiczy.mytv.core.data.entities.control

enum class ControlKey(val label: String) {
    ON_UP("上键 / 向下滑动"), ON_DOWN("下键 / 向上滑动"),
    ON_LEFT("左键 / 向右滑动"), ON_RIGHT("右键 / 向左滑动"), ON_SELECT("确认键"),
    ON_LONG_UP("长按上键"), ON_LONG_DOWN("长按下键"),
    ON_LONG_LEFT("长按左键"), ON_LONG_RIGHT("长按右键"), ON_LONG_SELECT("长按确认键"),
    ON_NUMBER("数字键"),
    ON_TOP_START("短按左上角"), ON_TOP_CENTER("短按顶部中央"), ON_TOP_END("短按右上角"),
    ON_BOTTOM_START("短按左下角"), ON_BOTTOM_CENTER("短按底部中央"), ON_BOTTOM_END("短按右下角"),
    ON_CENTER_START("短按左中央"), ON_CENTER_CENTER_START("短按中央偏左"),
    ON_CENTER_CENTER_CENTER("短按中央"), ON_CENTER_CENTER_END("短按中央偏右"), ON_CENTER_END("短按右中央"),
    ON_LONG_TOP_START("长按左上角"), ON_LONG_TOP_CENTER("长按顶部中央"), ON_LONG_TOP_END("长按右上角"),
    ON_LONG_BOTTOM_START("长按左下角"), ON_LONG_BOTTOM_CENTER("长按底部中央"), ON_LONG_BOTTOM_END("长按右下角"),
    ON_LONG_CENTER_START("长按左中央"), ON_LONG_CENTER_CENTER_START("长按中央偏左"),
    ON_LONG_CENTER_CENTER_CENTER("长按中央"), ON_LONG_CENTER_CENTER_END("长按中央偏右"), ON_LONG_CENTER_END("长按右中央");
}

enum class ControlAction(val label: String) {
    NONE("无操作"), PREVIOUS_CHANNEL("上一个频道"), NEXT_CHANNEL("下一个频道"),
    PREVIOUS_LINE("上一条线路"), NEXT_LINE("下一条线路"), CHANNEL_NO_SELECT("数字选台"),
    CHANNELS("频道列表"), QUICK_OP("快捷操作"), CHANNEL_SOURCE_LIST("订阅源列表"),
    EPG_PROGRAMME_LIST("节目单"), CHANNEL_LINE_LIST("线路列表"),
    VIDEO_PLAYER_DISPLAY_MODE_LIST("显示模式"), VIDEO_PLAYER_FACTORY_LIST("播放器内核"),
    VIDEO_PLAYER_VIDEO_TRACK_LIST("视轨"), VIDEO_PLAYER_AUDIO_TRACK_LIST("音轨"),
    VIDEO_PLAYER_SUBTITLE_TRACK_LIST("字幕"), VIDEO_PLAYER_CONTROLLER("播放控制");

    fun supports(key: ControlKey) = this != CHANNEL_NO_SELECT || key == ControlKey.ON_NUMBER
}

object ControlKeyActions {
    private val defaults = ControlKey.entries.associateWith { key ->
        when (key) {
            ControlKey.ON_UP -> ControlAction.PREVIOUS_CHANNEL
            ControlKey.ON_DOWN -> ControlAction.NEXT_CHANNEL
            ControlKey.ON_LEFT -> ControlAction.PREVIOUS_LINE
            ControlKey.ON_RIGHT -> ControlAction.NEXT_LINE
            ControlKey.ON_SELECT, ControlKey.ON_CENTER_CENTER_START,
            ControlKey.ON_CENTER_CENTER_CENTER, ControlKey.ON_CENTER_CENTER_END -> ControlAction.CHANNELS
            ControlKey.ON_LONG_DOWN, ControlKey.ON_BOTTOM_CENTER -> ControlAction.VIDEO_PLAYER_CONTROLLER
            ControlKey.ON_LONG_LEFT, ControlKey.ON_CENTER_START -> ControlAction.EPG_PROGRAMME_LIST
            ControlKey.ON_LONG_RIGHT, ControlKey.ON_CENTER_END -> ControlAction.CHANNEL_LINE_LIST
            ControlKey.ON_NUMBER -> ControlAction.CHANNEL_NO_SELECT
            else -> if (key.name.startsWith("ON_LONG_") && key != ControlKey.ON_LONG_UP) {
                ControlAction.QUICK_OP
            } else ControlAction.NONE
        }
    }

    fun actionFor(key: ControlKey, overrides: Map<String, String>, flipChannels: Boolean = false): ControlAction {
        val configured = ControlAction.entries.firstOrNull { it.name == overrides[key.name] }
        if (configured != null && configured.supports(key)) return configured
        if (flipChannels) {
            if (key == ControlKey.ON_UP) return ControlAction.NEXT_CHANNEL
            if (key == ControlKey.ON_DOWN) return ControlAction.PREVIOUS_CHANNEL
        }
        return defaults.getValue(key)
    }

    fun sanitize(overrides: Map<String, String>): Map<String, String> = overrides.filter { (name, actionName) ->
        val key = ControlKey.entries.firstOrNull { it.name == name }
        val action = ControlAction.entries.firstOrNull { it.name == actionName }
        key != null && action != null && action.supports(key)
    }

    /** Eleven touch regions: three across the top/bottom and five across the middle. */
    fun touchKey(x: Float, y: Float, longPress: Boolean): ControlKey {
        val region = when {
            y < 1f / 3f -> when { x < 1f / 3f -> "TOP_START"; x < 2f / 3f -> "TOP_CENTER"; else -> "TOP_END" }
            y >= 2f / 3f -> when { x < 1f / 3f -> "BOTTOM_START"; x < 2f / 3f -> "BOTTOM_CENTER"; else -> "BOTTOM_END" }
            else -> when {
                x < .2f -> "CENTER_START"; x < .4f -> "CENTER_CENTER_START"
                x < .6f -> "CENTER_CENTER_CENTER"; x < .8f -> "CENTER_CENTER_END"; else -> "CENTER_END"
            }
        }
        return ControlKey.valueOf("ON_${if (longPress) "LONG_" else ""}$region")
    }
}

/** A long press fires once and must not also fire a short press on release. */
class ControlKeyPressTracker {
    private val pressed = mutableMapOf<Int, Boolean>()

    fun down(keyCode: Int, repeatCount: Int): Boolean {
        if (repeatCount == 0) {
            pressed[keyCode] = false
            return false
        }
        if (pressed[keyCode] != false) return false
        pressed[keyCode] = true
        return true
    }

    fun up(keyCode: Int, cancelled: Boolean = false): Boolean = pressed.remove(keyCode) == false && !cancelled
    fun clear() = pressed.clear()
}
