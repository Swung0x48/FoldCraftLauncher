package com.tungsten.fcl.control

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.tungsten.fcl.control.data.QuickInputTexts
import com.tungsten.fcl.databinding.DialogQuickInputBinding
import com.tungsten.fclauncher.bridge.FCLBridge
import com.tungsten.fclauncher.keycodes.FCLKeycodes
import com.tungsten.fclauncher.keycodes.MinecraftKeyBindingMapper
import com.tungsten.fcllibrary.component.dialog.FCLDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class QuickInputDialog(private val activity: Activity, private val menu: GameMenu) :
    FCLDialog(activity),
    View.OnClickListener {
    private val binding: DialogQuickInputBinding

    init {
        setCancelable(false)
        window!!.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
        binding = DialogQuickInputBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.addText.setOnClickListener(this)
        binding.positive.setOnClickListener(this)

        refreshList(menu)
    }

    private fun refreshList(menu: GameMenu) {
        val adapter = InputTextAdapter(
            context,
            QuickInputTexts.getInputTexts()
        ) {
            if (it.isNotEmpty()) {
                val bridge = menu.bridge
                val usesSDL3 = bridge?.isUseSDL3 == true
                val cursorMode = if (usesSDL3) {
                    menu.input.syncCursorModeForInput()
                } else {
                    menu.cursorMode
                }
                if (cursorMode == FCLBridge.CursorEnabled) {
                    if (usesSDL3) {
                        menu.input.sendTextWhenReady(it)
                    } else {
                        menu.input.sendText(it)
                    }
                } else {
                    val gameOption = menu.gameOption
                    menu.input.sendBoundKeyEvent(
                        gameOption,
                        MinecraftKeyBindingMapper.BINDING_CHAT,
                        FCLKeycodes.KEY_T,
                        true
                    )
                    menu.input.sendBoundKeyEvent(
                        gameOption,
                        MinecraftKeyBindingMapper.BINDING_CHAT,
                        FCLKeycodes.KEY_T,
                        false
                    )
                    if (usesSDL3) {
                        menu.input.sendTextWhenReady(it, true)
                    } else {
                        (activity as LifecycleOwner).lifecycleScope.launch {
                            delay(50)
                            sendText(it, submit = true)
                        }
                    }
                }
            }

            dismiss()
        }
        binding.list.setAdapter(adapter)
    }

    private fun sendText(text: String, submit: Boolean) {
        menu.input.sendText(text)
        if (submit) {
            menu.input.sendKeyEvent(FCLKeycodes.KEY_ENTER, true)
            menu.input.sendKeyEvent(FCLKeycodes.KEY_ENTER, false)
        }
    }

    override fun onClick(v: View?) {
        when (v) {
            binding.addText -> AddInputTextDialog(
                context
            ) { refreshList(menu) }.show()

            binding.positive -> dismiss()
        }
    }
}
