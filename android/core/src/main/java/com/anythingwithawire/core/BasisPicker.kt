package com.anythingwithawire.core

import android.app.Activity
import android.app.AlertDialog
import android.view.View
import android.widget.RadioButton
import android.widget.RadioGroup

/** The per-session basis-for-listening choice, shared so every app applies the same legal gate. */
class BasisPicker(private val group: RadioGroup) {

    init {
        for (basis in Basis.entries) {
            group.addView(RadioButton(group.context).apply {
                id = View.generateViewId()
                tag = basis
                text = basis.label
                textSize = 13f
            })
        }
    }

    /** The chosen basis, or null when none is chosen. */
    val selected: Basis?
        get() {
            val checked = group.checkedRadioButtonId
            if (checked == View.NO_ID) return null
            return group.findViewById<RadioButton>(checked).tag as Basis
        }

    fun setEnabled(enabled: Boolean) {
        for (i in 0 until group.childCount) group.getChildAt(i).isEnabled = enabled
    }

    fun clear() = group.clearCheck()

    /** Runs [proceed] straight away, or after the user confirms the narrow lawful-interests exception. */
    fun confirmThen(activity: Activity, basis: Basis, proceed: () -> Unit) {
        if (basis != Basis.LAWFUL_INTERESTS) {
            proceed()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle("Lawful interests: a narrow exception")
            .setMessage("Surveillance Devices Act 1998 (WA) s 5(3)(d) needs you to be a principal party and the use to be " +
                "\"reasonably necessary for the protection of the lawful interests\" of that party. s 9(2)(a)(vi) is the " +
                "matching exception for communicating the conversation. You carry the burden of justifying it. Continue?")
            .setPositiveButton("Continue") { _, _ -> proceed() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        const val NONE_CHOSEN = "Choose the basis for listening first. Recording a private conversation, or communicating one, " +
            "without an exception can be an offence under the Surveillance Devices Act 1998 (WA) ss 5 and 9."
    }
}
