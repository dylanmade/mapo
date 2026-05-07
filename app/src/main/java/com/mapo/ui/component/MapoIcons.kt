package com.mapo.ui.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.BrightnessLow
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Curated catalog of Material icons exposed to the per-button icon picker.
 *
 * The user picks an icon by name (string), and the renderer + picker both look up the
 * same name → [ImageVector] map here. Curated rather than reflective so the picker
 * stays scrollable and binary size doesn't carry the entire extended icon set.
 */
object MapoIcons {

    val catalog: Map<String, ImageVector> = linkedMapOf(
        // Direction / navigation
        "ArrowUpward" to Icons.Filled.ArrowUpward,
        "ArrowDownward" to Icons.Filled.ArrowDownward,
        "ArrowDropUp" to Icons.Filled.ArrowDropUp,
        "ArrowDropDown" to Icons.Filled.ArrowDropDown,
        "KeyboardArrowUp" to Icons.Filled.KeyboardArrowUp,
        "KeyboardArrowDown" to Icons.Filled.KeyboardArrowDown,

        // Input / gaming
        "Mouse" to Icons.Filled.Mouse,
        "Keyboard" to Icons.Filled.Keyboard,
        "TouchApp" to Icons.Filled.TouchApp,
        "Gesture" to Icons.Filled.Gesture,
        "Gamepad" to Icons.Filled.Gamepad,
        "SportsEsports" to Icons.Filled.SportsEsports,
        "VideogameAsset" to Icons.Filled.VideogameAsset,

        // Media transport
        "PlayArrow" to Icons.Filled.PlayArrow,
        "Pause" to Icons.Filled.Pause,
        "Stop" to Icons.Filled.Stop,
        "FastForward" to Icons.Filled.FastForward,
        "FastRewind" to Icons.Filled.FastRewind,
        "SkipNext" to Icons.Filled.SkipNext,
        "SkipPrevious" to Icons.Filled.SkipPrevious,

        // Audio
        "Mic" to Icons.Filled.Mic,
        "MicOff" to Icons.Filled.MicOff,

        // System / actions
        "Settings" to Icons.Filled.Settings,
        "Tune" to Icons.Filled.Tune,
        "Power" to Icons.Filled.Power,
        "PowerSettingsNew" to Icons.Filled.PowerSettingsNew,
        "Refresh" to Icons.Filled.Refresh,
        "Search" to Icons.Filled.Search,
        "Close" to Icons.Filled.Close,
        "Done" to Icons.Filled.Done,
        "Add" to Icons.Filled.Add,
        "Remove" to Icons.Filled.Remove,
        "Edit" to Icons.Filled.Edit,
        "Delete" to Icons.Filled.Delete,
        "Save" to Icons.Filled.Save,
        "Menu" to Icons.Filled.Menu,
        "MoreVert" to Icons.Filled.MoreVert,
        "MoreHoriz" to Icons.Filled.MoreHoriz,
        "Home" to Icons.Filled.Home,

        // Symbols / status
        "Star" to Icons.Filled.Star,
        "Favorite" to Icons.Filled.Favorite,
        "FavoriteBorder" to Icons.Filled.FavoriteBorder,
        "Bookmark" to Icons.Filled.Bookmark,
        "ThumbUp" to Icons.Filled.ThumbUp,
        "ThumbDown" to Icons.Filled.ThumbDown,
        "Flag" to Icons.Filled.Flag,
        "Lock" to Icons.Filled.Lock,
        "LockOpen" to Icons.Filled.LockOpen,
        "Visibility" to Icons.Filled.Visibility,
        "VisibilityOff" to Icons.Filled.VisibilityOff,

        // Alerts
        "Warning" to Icons.Filled.Warning,
        "Error" to Icons.Filled.Error,
        "Info" to Icons.Filled.Info,
        "CheckCircle" to Icons.Filled.CheckCircle,
        "Cancel" to Icons.Filled.Cancel,

        // Display / misc
        "BrightnessHigh" to Icons.Filled.BrightnessHigh,
        "BrightnessLow" to Icons.Filled.BrightnessLow,
        "Wifi" to Icons.Filled.Wifi,
        "Bluetooth" to Icons.Filled.Bluetooth,
        "SwapHoriz" to Icons.Filled.SwapHoriz,
        "SwapVert" to Icons.Filled.SwapVert,
        "ZoomIn" to Icons.Filled.ZoomIn,
        "ZoomOut" to Icons.Filled.ZoomOut,
    )

    fun resolve(name: String?): ImageVector? = name?.let { catalog[it] }
}
