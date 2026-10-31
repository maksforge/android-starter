package app.starter.ui.common

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.PendingIntentCompat
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import java.io.File
import java.util.UUID

/**
 * Hands [files] to the share sheet through the app's FileProvider.
 *
 * MIUI 12.5's share sheet starts the picked app as the system rather than as us, and the platform
 * refuses a grant made that way ("use startActivityAsCaller() instead"), so the picked app finds no
 * provider and cannot open the file. Granting every app that takes the share up front, as ourselves,
 * survives that. [ShareChosenReceiver] takes it back from all but the pick, [revokeUnpickedShare]
 * from all of them after a cancel, and the next share from the same directory from the pick.
 *
 * Every file must sit in one of `res/xml/file_paths.xml`'s directories; the first path segment of its
 * Uri is that directory's name, which is how a later share revokes what an earlier one granted.
 */
fun Context.shareFiles(
    files: List<File>,
    mime: String,
    title: CharSequence? = null,
) {
    val uris = files.map { FileProvider.getUriForFile(this, fileProviderAuthority, it) }
    val send =
        Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = mime
            if (uris.size == 1) {
                putExtra(Intent.EXTRA_STREAM, uris.single())
            } else {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(uris))
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    val takers =
        packageManager
            .queryIntentActivities(send, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }
            .distinct()
    uris.map { it.pathSegments.first() }.distinct().forEach { revokeShares(it) }
    for (taker in takers) {
        for (uri in uris) grantUriPermission(taker, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val open = OpenShare(UUID.randomUUID().toString(), takers, uris)
    shares.opened(open)
    startActivity(Intent.createChooser(send, title, shareChosenCallback(open.id, takers, uris)?.intentSender))
}

internal fun Context.shareChosenCallback(
    shareId: String,
    takers: List<String>,
    uris: List<Uri>,
): PendingIntent? {
    val onChosen =
        Intent(this, ShareChosenReceiver::class.java)
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            .putExtra(EXTRA_SHARE, shareId)
            .putStringArrayListExtra(EXTRA_TAKERS, ArrayList(takers))
            .putParcelableArrayListExtra(EXTRA_URIS, ArrayList<Uri>(uris))
    // One request code per share: with a shared one, every sheet's callback is the same PendingIntent
    // and a newer share rewrites the extras an older sheet will send back.
    return PendingIntentCompat.getBroadcast(this, shareId.hashCode(), onChosen, PendingIntent.FLAG_UPDATE_CURRENT, true)
}

private class OpenShare(
    val id: String,
    val takers: List<String>,
    val uris: List<Uri>,
)

private val shares = ShareBook<OpenShare> { it.id }

/**
 * For the activity that opened the sheet, once it is back: a sheet with no pick heard is taken as
 * closed without one. A trampoline target that hands off to a service and finishes can beat its own
 * pick here, which [ShareChosenReceiver] makes good.
 */
fun Context.revokeUnpickedShare() {
    val open = shares.returned() ?: return
    revoke(open.takers, open.uris)
}

/** Every app's read access to what was shared from one of `file_paths.xml`'s directories, by its [root] name. */
fun Context.revokeShares(root: String) =
    revokeUriPermission(
        Uri
            .Builder()
            .scheme(ContentResolver.SCHEME_CONTENT)
            .authority(fileProviderAuthority)
            .appendPath(root)
            .build(),
        Intent.FLAG_GRANT_READ_URI_PERMISSION,
    )

/** The share sheet's word on which app was picked, filled into the broadcast [shareFiles] hands it. */
class ShareChosenReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val picked =
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_CHOSEN_COMPONENT, ComponentName::class.java)
                ?: return
        val uris = IntentCompat.getParcelableArrayListExtra(intent, EXTRA_URIS, Uri::class.java).orEmpty()
        if (shares.picked(intent.getStringExtra(EXTRA_SHARE))) {
            for (uri in uris) context.grantUriPermission(picked.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.revoke(intent.getStringArrayListExtra(EXTRA_TAKERS).orEmpty().filter { it != picked.packageName }, uris)
    }
}

private fun Context.revoke(
    takers: List<String>,
    uris: List<Uri>,
) {
    for (taker in takers) {
        for (uri in uris) revokeUriPermission(taker, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

private const val EXTRA_SHARE = "share_id"
private const val EXTRA_TAKERS = "share_takers"
private const val EXTRA_URIS = "share_uris"

private val Context.fileProviderAuthority get() = "$packageName.fileprovider"
