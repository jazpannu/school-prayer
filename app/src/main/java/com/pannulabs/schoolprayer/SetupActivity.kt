package com.pannulabs.schoolprayer

import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.pannulabs.schoolprayer.databinding.ActivitySetupBinding

/** Shown on first launch: asks for the school name, then opens the home screen. */
class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.schoolInput.doAfterTextChanged { binding.schoolLayout.error = null }
        binding.schoolInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { save(); true } else false
        }
        binding.btnContinue.setOnClickListener { save() }
        binding.schoolInput.requestFocus()
    }

    private fun save() {
        val name = binding.schoolInput.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            binding.schoolLayout.error = getString(R.string.school_name_required)
            return
        }
        TrackStore(this).schoolName = name
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
