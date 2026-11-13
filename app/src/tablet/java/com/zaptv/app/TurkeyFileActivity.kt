package com.zaptv.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Loads, replaces or removes the Turkey (WireGuard) file on the tablet.
 * Opens Android's file picker so the .conf can be picked from Downloads. No laptop needed.
 */
class TurkeyFileActivity : Activity() {
    companion object { private const val REQ_PICK = 21 }

    private val vpn get() = TrVpn.get(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        if (!vpn.configured) { pick(); return }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Turkey file")
            .setMessage("A Turkey file is loaded. ${vpn.channels.size} channel(s) use the Turkey connection.")
            .setPositiveButton("Replace") { _, _ -> pick() }
            .setNeutralButton("Remove") { _, _ -> confirmRemove() }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun confirmRemove() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Remove the Turkey file?")
            .setMessage("Channels marked TR go back to the normal connection until you load a file again.")
            .setPositiveButton("Remove") { _, _ -> vpn.removeConfig(); toast("Turkey file removed"); finish() }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun pick() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        val ok = runCatching { startActivityForResult(i, REQ_PICK) }.isSuccess
        if (!ok) { toast("This tablet has no file picker"); finish() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK) return
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) { finish(); return }
        val text = runCatching {
            contentResolver.openInputStream(uri)?.use { s -> s.readBytes().take(64 * 1024).toByteArray().toString(Charsets.UTF_8) }
        }.getOrNull()
        val err = if (text == null) "Couldn't open that file" else vpn.saveConfig(text)
        toast(err ?: "Turkey file loaded. In ZapTV, open Options on a channel and turn Turkey connection on.")
        finish()
    }

    private fun toast(msg: String) = Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
}
