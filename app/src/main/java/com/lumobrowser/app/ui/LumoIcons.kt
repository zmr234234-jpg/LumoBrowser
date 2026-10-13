package com.lumobrowser.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The complete icon set, in one place.
 *
 * Every screen takes its icons from here so a visual change (or a future switch to a custom icon
 * pack) happens in a single file. All of these come from the bundled `material-icons-extended`
 * artifact, which keeps the APK free of image assets — there is not a single PNG in the project.
 *
 * Directional glyphs (arrows, list, sort, volume) are taken from the **AutoMirrored** set: they
 * flip automatically when the layout direction is RTL. Using the plain `Icons.Filled` variants
 * would leave a back arrow pointing the wrong way in a right-to-left locale.
 */
object LumoIcons {
    val Add: ImageVector = Icons.Filled.Add
    val Search: ImageVector = Icons.Filled.Search
    val Clear: ImageVector = Icons.Filled.Clear
    val Close: ImageVector = Icons.Filled.Close
    val Settings: ImageVector = Icons.Filled.Settings
    val Back: ImageVector = Icons.AutoMirrored.Filled.ArrowBack
    val Forward: ImageVector = Icons.AutoMirrored.Filled.ArrowForward
    val Refresh: ImageVector = Icons.Filled.Refresh
    val Home: ImageVector = Icons.Filled.Home
    val Share: ImageVector = Icons.Filled.Share
    val OpenExternal: ImageVector = Icons.AutoMirrored.Filled.OpenInNew
    val Edit: ImageVector = Icons.Filled.Edit
    val Delete: ImageVector = Icons.Filled.Delete
    val Pin: ImageVector = Icons.Filled.PushPin
    val PinOff: ImageVector = Icons.Filled.KeyboardArrowUp
    val Favorite: ImageVector = Icons.Filled.FavoriteBorder
    val Check: ImageVector = Icons.Filled.Check
    val CheckCircle: ImageVector = Icons.Filled.CheckCircle
    val Warning: ImageVector = Icons.Filled.Warning
    val Error: ImageVector = Icons.Filled.ErrorOutline
    val Info: ImageVector = Icons.Filled.Info
    val Copy: ImageVector = Icons.Filled.ContentCopy
    val Download: ImageVector = Icons.Filled.Download
    val Upload: ImageVector = Icons.Filled.Upload
    val Restore: ImageVector = Icons.Filled.Restore
    val Palette: ImageVector = Icons.Filled.Palette
    val Tune: ImageVector = Icons.Filled.Tune
    val Language: ImageVector = Icons.Filled.Language
    val Sound: ImageVector = Icons.AutoMirrored.Filled.VolumeUp
    val Mute: ImageVector = Icons.AutoMirrored.Filled.VolumeOff
    val Grid: ImageVector = Icons.Filled.GridView
    val Rows: ImageVector = Icons.AutoMirrored.Filled.List
    val Sort: ImageVector = Icons.AutoMirrored.Filled.Sort
    val More: ImageVector = Icons.Filled.MoreVert

    /** Leaves a page-owned fullscreen view. Required, not decorative: see `PageFullscreenOverlay`. */
    val FullscreenExit: ImageVector = Icons.Filled.FullscreenExit

    // Network and VPN assistance.
    val Wifi: ImageVector = Icons.Filled.Wifi
    val WifiOff: ImageVector = Icons.Filled.WifiOff
    val Vpn: ImageVector = Icons.Filled.VpnKey
    val Shield: ImageVector = Icons.Filled.Security
    val Lock: ImageVector = Icons.Filled.Lock
    val Speed: ImageVector = Icons.Filled.Speed
}
